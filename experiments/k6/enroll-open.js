import grpc from 'k6/net/grpc';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

const TARGET = __ENV.TARGET || 'localhost:8080';
const PROTO_DIR = __ENV.PROTO_DIR || '../../Proto/src/main/proto';
const STUDENTS = Number(__ENV.STUDENTS || 3660);
const FIRST_ID = Number(__ENV.FIRST_ID || 30000001);
const PASSWORD = __ENV.PASSWORD || 'loadtest0811';
const COUNTDOWN_S = Number(__ENV.COUNTDOWN_S || 10);
const ARRIVAL_SPREAD_S = Number(__ENV.ARRIVAL_SPREAD_S || 2);
const PICKS_MAX = Number(__ENV.PICKS_MAX || 2);
const OFFERING_MIN = Number(__ENV.OFFERING_MIN || 101);
const OFFERING_MAX = Number(__ENV.OFFERING_MAX || 110);
const SKEW = Number(__ENV.SKEW || 1.5);
const THINK_MS = Number(__ENV.THINK_MS || 500);
const ADMIT_TIMEOUT_S = Number(__ENV.ADMIT_TIMEOUT_S || 1800);
const OPEN_RETRY_S = Number(__ENV.OPEN_RETRY_S || 15);
const RETRIES = Number(__ENV.RETRIES || 3);
const RPC_TIMEOUT = __ENV.RPC_TIMEOUT || '30s';
const SUMMARY_DIR = __ENV.SUMMARY_DIR || '.';
const CONFIG = __ENV.CONFIG || 'adhoc';
const FULL = '정원이 마감되었습니다.';
const AUTH = 'serverarchitecture.AuthService/';
const ENROLLMENT = 'serverarchitecture.EnrollmentService/';
const codes = (...list) => new Set(list.map(Number));
const BUSINESS = codes(grpc.StatusAlreadyExists, grpc.StatusAborted, grpc.StatusOutOfRange, grpc.StatusNotFound);
const RETRYABLE = codes(grpc.StatusUnavailable, grpc.StatusDeadlineExceeded, grpc.StatusResourceExhausted);

const client = new grpc.Client();
client.load([PROTO_DIR], 'auth.proto', 'enrollment.proto');

const rpcErrors = new Counter('rpc_errors');
const rpcErrorRate = new Rate('rpc_error_rate');
const loginOk = new Counter('students_logged_in');
const admitted = new Counter('students_admitted');
const finished = new Counter('students_finished');
const failed = new Counter('students_failed');
const timeToAdmit = new Trend('time_to_admit', true);
const watchUpdates = new Counter('watch_updates');
const watchReconnects = new Counter('watch_reconnects');
const enrollSuccess = new Counter('enroll_success');
const enrollFull = new Counter('enroll_full');
const enrollRejected = new Counter('enroll_rejected');
const enrollError = new Counter('enroll_error');
const enrollErrorRate = new Rate('enroll_error_rate');
const myListMismatch = new Counter('my_list_mismatch');
const negativeRemaining = new Counter('negative_remaining');
const released = new Counter('passes_released');
const sessionTime = new Trend('session_time', true);

export const options = {
  setupTimeout: '2m',
  scenarios: {
    open_rush: {
      executor: 'per-vu-iterations',
      vus: STUDENTS,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '40m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    enroll_error_rate: ['rate<0.04'],
    rpc_error_rate: ['rate<0.04'],
    'grpc_req_duration{name:enroll}': ['p(95)<=2000'],
    'grpc_req_duration{name:login}': ['max>=0'],
    'grpc_req_duration{name:enter_queue}': ['max>=0'],
    'grpc_req_duration{name:offerings}': ['max>=0'],
    'grpc_req_duration{name:my_enrollments}': ['max>=0'],
    'grpc_req_duration{name:leave_queue}': ['max>=0'],
    students_failed: ['count<1'],
    negative_remaining: ['count<1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const startAt = Date.now() + COUNTDOWN_S * 1000;
  console.log(`CONFIG=${CONFIG} TARGET=${TARGET} STUDENTS=${STUDENTS} OPEN_AT_MS=${startAt}`);
  return { startAt };
}

function sleepUntil(epochMs) {
  const wait = epochMs - Date.now();
  if (wait > 0) sleep(wait / 1000);
}

function params(name, token) {
  const p = { tags: { name }, timeout: RPC_TIMEOUT };
  if (token) p.metadata = { authorization: `Bearer ${token}` };
  return p;
}

function call(method, body, name, token) {
  return client.invoke(method, body, params(name, token));
}

function isError(res, businessCodes) {
  if (res.status === grpc.StatusOK) return false;
  return !businessCodes.has(Number(res.status));
}

function record(res, name, businessCodes) {
  const bad = isError(res, businessCodes || new Set());
  rpcErrorRate.add(bad, { name });
  if (bad) rpcErrors.add(1, { name, code: String(res.status) });
  return bad;
}

function withRetry(method, body, name, token, businessCodes) {
  let res;
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    res = call(method, body, name, token);
    const bad = record(res, name, businessCodes);
    if (!bad || !RETRYABLE.has(Number(res.status))) return res;
    sleep(Math.min(4, 0.5 * Math.pow(2, attempt)) * (0.5 + Math.random()));
  }
  return res;
}

function login(userId) {
  const res = withRetry(`${AUTH}Login`, { user_id: userId, password: PASSWORD }, 'login');
  return res.status === grpc.StatusOK ? res.message.token : null;
}

function enterQueue(token, startAt) {
  for (;;) {
    const res = call(`${ENROLLMENT}EnterQueue`, {}, 'enter_queue', token);
    const early = res.status === grpc.StatusFailedPrecondition && Date.now() < startAt + OPEN_RETRY_S * 1000;
    if (!early) {
      record(res, 'enter_queue');
      return res;
    }
    sleep(0.2);
  }
}

function watch(token) {
  return new Promise((resolve) => {
    let state = '';
    let error = null;
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      resolve({ state, error });
    };
    const stream = new grpc.Stream(client, `${ENROLLMENT}WatchQueue`, params('watch_queue', token));
    stream.on('data', (m) => {
      watchUpdates.add(1);
      state = m.state;
    });
    stream.on('error', (e) => {
      error = e;
      finish();
    });
    stream.on('end', finish);
    stream.write({});
    stream.end();
  });
}

async function waitAdmitted(token, startAt) {
  let res = enterQueue(token, startAt);
  if (res.status !== grpc.StatusOK) return false;
  let state = res.message.state;
  const deadline = Date.now() + ADMIT_TIMEOUT_S * 1000;
  let errors = 0;
  while (state !== 'ADMITTED') {
    if (Date.now() > deadline) return false;
    const result = await watch(token);
    if (result.error) {
      rpcErrorRate.add(true, { name: 'watch_queue' });
      rpcErrors.add(1, { name: 'watch_queue', code: String(result.error.code) });
      errors++;
      if (errors > RETRIES * 3) return false;
      watchReconnects.add(1);
      sleep(Math.min(5, errors) * (0.5 + Math.random()));
      continue;
    }
    rpcErrorRate.add(false, { name: 'watch_queue' });
    state = result.state;
    if (state === 'NOT_IN_QUEUE') {
      res = enterQueue(token, startAt);
      if (res.status !== grpc.StatusOK) return false;
      state = res.message.state;
    } else if (state !== 'ADMITTED') {
      watchReconnects.add(1);
    }
  }
  return true;
}

function pickOfferings(offerings) {
  const byCourse = {};
  for (const o of offerings) {
    if (o.offeringId < OFFERING_MIN || o.offeringId > OFFERING_MAX) continue;
    (byCourse[o.courseId] = byCourse[o.courseId] || []).push(o);
  }
  const courses = Object.keys(byCourse).sort();
  const count = 1 + Math.floor(Math.random() * PICKS_MAX);
  const picks = [];
  const used = new Set();
  for (let tries = 0; picks.length < count && tries < count * 10 && used.size < courses.length; tries++) {
    const course = courses[Math.floor(courses.length * Math.pow(Math.random(), SKEW))];
    if (used.has(course)) continue;
    used.add(course);
    const sections = byCourse[course];
    picks.push(sections[Math.floor(Math.random() * sections.length)]);
  }
  return picks;
}

function enroll(token, offering) {
  const res = call(`${ENROLLMENT}Enroll`, { offering_id: offering.offeringId }, 'enroll', token);
  const full = res.status === grpc.StatusResourceExhausted && res.error && res.error.message === FULL;
  const bad = !full && isError(res, BUSINESS);
  rpcErrorRate.add(bad, { name: 'enroll' });
  enrollErrorRate.add(bad);
  if (bad) {
    rpcErrors.add(1, { name: 'enroll', code: String(res.status) });
    enrollError.add(1, { code: String(res.status) });
    return false;
  }
  if (full) {
    enrollFull.add(1);
    return false;
  }
  if (res.status !== grpc.StatusOK) {
    enrollRejected.add(1, { code: String(res.status) });
    return false;
  }
  enrollSuccess.add(1);
  if (res.message.offering && res.message.offering.remaining < 0) negativeRemaining.add(1);
  return true;
}

export default async function (data) {
  const userId = String(FIRST_ID + exec.vu.idInTest - 1);
  sleepUntil(data.startAt + Math.random() * ARRIVAL_SPREAD_S * 1000);

  try {
    client.connect(TARGET, { plaintext: true, timeout: '30s' });
  } catch (e) {
    rpcErrorRate.add(true, { name: 'connect' });
    rpcErrors.add(1, { name: 'connect', code: 'connect' });
    failed.add(1, { stage: 'connect' });
    return;
  }
  const token = login(userId);
  if (!token) {
    failed.add(1, { stage: 'login' });
    client.close();
    return;
  }
  loginOk.add(1);

  if (!(await waitAdmitted(token, data.startAt))) {
    failed.add(1, { stage: 'queue' });
    client.close();
    return;
  }
  const admittedAt = Date.now();
  admitted.add(1);
  timeToAdmit.add(admittedAt - data.startAt);

  const list = withRetry(`${ENROLLMENT}ListOfferings`, {}, 'offerings', token);
  const offerings = list.status === grpc.StatusOK ? list.message.offerings || [] : [];
  if (offerings.length === 0) {
    failed.add(1, { stage: 'offerings' });
    call(`${ENROLLMENT}LeaveQueue`, {}, 'leave_queue', token);
    client.close();
    return;
  }

  let mine = 0;
  for (const offering of pickOfferings(offerings)) {
    sleep((THINK_MS * (0.5 + Math.random())) / 1000);
    if (enroll(token, offering)) mine++;
  }

  const my = withRetry(`${ENROLLMENT}ListMyEnrollments`, {}, 'my_enrollments', token);
  if (my.status === grpc.StatusOK) {
    if ((my.message.offerings || []).length !== mine) myListMismatch.add(1);
  }

  const leave = withRetry(`${ENROLLMENT}LeaveQueue`, {}, 'leave_queue', token);
  if (leave.status === grpc.StatusOK && leave.message.released) released.add(1);
  sessionTime.add(Date.now() - admittedAt);
  if (my.status === grpc.StatusOK) {
    finished.add(1);
  } else {
    failed.add(1, { stage: 'my_enrollments' });
  }
  client.close();
}

function metric(data, name, stat) {
  const m = data.metrics[name];
  if (!m) return 0;
  const v = m.values[stat];
  return v === undefined ? 0 : v;
}

function ms(v) {
  return v >= 10000 ? `${(v / 1000).toFixed(1)}s` : `${Math.round(v)}ms`;
}

function lat(data, name) {
  const key = `grpc_req_duration{name:${name}}`;
  return `p95 ${ms(metric(data, key, 'p(95)'))}, p99 ${ms(metric(data, key, 'p(99)'))}, 최대 ${ms(metric(data, key, 'max'))}`;
}

export function handleSummary(data) {
  const startAt = data.setup_data ? data.setup_data.startAt : 0;
  const success = metric(data, 'enroll_success', 'count');
  const full = metric(data, 'enroll_full', 'count');
  const rejected = metric(data, 'enroll_rejected', 'count');
  const error = metric(data, 'enroll_error', 'count');
  const attempts = success + full + rejected + error;
  const enrollPct = attempts ? (error / attempts) * 100 : 0;
  const rpcPct = metric(data, 'rpc_error_rate', 'rate') * 100;
  const failedThresholds = Object.entries(data.metrics)
    .flatMap(([name, m]) => Object.entries(m.thresholds || {}).filter(([, t]) => !t.ok).map(([expr]) => `${name} ${expr}`));
  const lines = [
    '',
    `================ 수강신청 오픈 부하 결과 ${CONFIG} ================`,
    `  대상                    ${TARGET}`,
    `  학생 수                 ${STUDENTS}명 (${ARRIVAL_SPREAD_S}초 안에 도착)`,
    `  로그인 성공             ${metric(data, 'students_logged_in', 'count')}명`,
    `  입장                    ${metric(data, 'students_admitted', 'count')}명, 끝까지 마침 ${metric(data, 'students_finished', 'count')}명, 실패 ${metric(data, 'students_failed', 'count')}명`,
    `  입장 대기               중앙값 ${ms(metric(data, 'time_to_admit', 'med'))}, p95 ${ms(metric(data, 'time_to_admit', 'p(95)'))}, 마지막 ${ms(metric(data, 'time_to_admit', 'max'))}`,
    `  대기열 스트림           갱신 ${metric(data, 'watch_updates', 'count')}회, 재연결 ${metric(data, 'watch_reconnects', 'count')}회`,
    `  수강신청 시도           ${attempts}건 (성공 ${success}, 정원 마감 ${full}, 기타 거절 ${rejected}, 오류 ${error})`,
    `  수강신청 오류율         ${enrollPct.toFixed(2)}%`,
    `  전체 RPC 오류율         ${rpcPct.toFixed(2)}% (${metric(data, 'rpc_errors', 'count')}건)`,
    `  로그인                  ${lat(data, 'login')}`,
    `  대기열 진입             ${lat(data, 'enter_queue')}`,
    `  강의 목록               ${lat(data, 'offerings')}`,
    `  수강신청                ${lat(data, 'enroll')}`,
    `  내 신청 목록            ${lat(data, 'my_enrollments')} (불일치 ${metric(data, 'my_list_mismatch', 'count')}명)`,
    `  입장권 반납             ${lat(data, 'leave_queue')} (반납 ${metric(data, 'passes_released', 'count')}명)`,
    `  남은 좌석 음수 응답     ${metric(data, 'negative_remaining', 'count')}건`,
    `  임계값                  ${failedThresholds.length === 0 ? '모두 통과' : '실패: ' + failedThresholds.join(', ')}`,
    '==================================================================',
    '',
  ].join('\n');
  const env = [
    `K6_CONFIG=${CONFIG}`,
    `K6_TARGET=${TARGET}`,
    `K6_STUDENTS=${STUDENTS}`,
    `K6_LOGGED_IN=${metric(data, 'students_logged_in', 'count')}`,
    `K6_ADMITTED=${metric(data, 'students_admitted', 'count')}`,
    `K6_FINISHED=${metric(data, 'students_finished', 'count')}`,
    `K6_FAILED=${metric(data, 'students_failed', 'count')}`,
    `K6_ATTEMPTS=${attempts}`,
    `K6_SUCCESS=${success}`,
    `K6_FULL=${full}`,
    `K6_REJECTED=${rejected}`,
    `K6_ERRORS=${error}`,
    `K6_ENROLL_ERROR_PCT=${enrollPct.toFixed(2)}`,
    `K6_RPC_ERROR_PCT=${rpcPct.toFixed(2)}`,
    `K6_ENROLL_P95=${Math.round(metric(data, 'grpc_req_duration{name:enroll}', 'p(95)'))}`,
    `K6_ENROLL_P99=${Math.round(metric(data, 'grpc_req_duration{name:enroll}', 'p(99)'))}`,
    `K6_LOGIN_P95=${Math.round(metric(data, 'grpc_req_duration{name:login}', 'p(95)'))}`,
    `K6_LAST_ADMIT_MS=${Math.round(metric(data, 'time_to_admit', 'max'))}`,
    `K6_NEGATIVE_REMAINING=${metric(data, 'negative_remaining', 'count')}`,
    `K6_MY_LIST_MISMATCH=${metric(data, 'my_list_mismatch', 'count')}`,
    `K6_START_AT_MS=${startAt}`,
    '',
  ].join('\n');
  return {
    stdout: lines,
    [`${SUMMARY_DIR}/summary.json`]: JSON.stringify(data, null, 2),
    [`${SUMMARY_DIR}/summary.txt`]: lines,
    [`${SUMMARY_DIR}/result.env`]: env,
  };
}
