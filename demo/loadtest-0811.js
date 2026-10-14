import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://192.168.64.30/api';
const TOKENS_FILE = __ENV.TOKENS_FILE || '.tokens.json';
const TOKENS = new SharedArray('tokens', () => JSON.parse(open(TOKENS_FILE)).tokens);
const STUDENTS = Math.min(Number(__ENV.STUDENTS || 3660), TOKENS.length);
const COUNTDOWN_S = Number(__ENV.COUNTDOWN_S || 5);
const PICKS = Number(__ENV.PICKS || 6);
const ARRIVAL_SPREAD_S = Number(__ENV.ARRIVAL_SPREAD_S || 2);
const SKEW = Number(__ENV.SKEW || 2.5);
const SUMMARY_DIR = __ENV.SUMMARY_DIR || 'results';
const LAST_RUN_FILE = __ENV.LAST_RUN_FILE || 'results/last-run.env';
const MODE = __ENV.MODE || '';
const FULL = '정원이 마감되었습니다.';

const START_AT = __ENV.START_AT || '';
const START_AT_MS = Number(__ENV.START_AT_MS || 0);
const SESSION_S = Number(__ENV.SESSION_S || 3);
const PREADMITTED = __ENV.PREADMITTED === '1';
const OPEN_RETRY_S = 15;
const ENTER_EXPECTED = http.expectedStatuses(200, 400);

const admitted = new Counter('students_admitted');
const gaveUp = new Counter('students_gave_up');
const timeToAdmit = new Trend('time_to_admit', true);
const queuePolls = new Counter('queue_polls');
const enrollSuccess = new Counter('enroll_success');
const enrollFull = new Counter('enroll_full');
const enrollRejected = new Counter('enroll_rejected');
const enrollError = new Counter('enroll_error');
const enrollErrorRate = new Rate('enroll_error_rate');
const lastSuccessAt = new Trend('enroll_success_offset', true);
const sessionTime = new Trend('session_time', true);
const released = new Counter('passes_released');
const releaseFailed = new Counter('release_failed');

export const options = {
  setupTimeout: '2m',
  teardownTimeout: '1m',
  scenarios: {
    registration_0811: {
      executor: 'per-vu-iterations',
      vus: STUDENTS,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '40m',
      gracefulStop: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    enroll_error_rate: ['rate<0.01'],
    'http_req_duration{name:enroll}': ['p(95)<1000'],
    'http_req_duration{name:queue_poll}': ['p(95)<500'],
    'http_req_duration{name:enter_queue}': ['p(95)<1000'],
    'http_req_duration{name:offerings}': ['p(95)<2000'],
    'http_req_duration{name:leave_queue}': ['p(95)<1000'],
    students_gave_up: ['count<1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

function parseStartAt(text) {
  const [h, m, s] = text.split(':').map(Number);
  const target = new Date();
  target.setHours(h, m, s || 0, 0);
  return target.getTime();
}

function clock(epochMs) {
  const d = new Date(epochMs);
  return [d.getHours(), d.getMinutes(), d.getSeconds()].map((v) => String(v).padStart(2, '0')).join(':');
}

const DIGITS = {
  0: ['█████', '█   █', '█   █', '█   █', '█████'],
  1: ['  █  ', ' ██  ', '  █  ', '  █  ', ' ███ '],
  2: ['█████', '    █', '█████', '█    ', '█████'],
  3: ['█████', '    █', '█████', '    █', '█████'],
  4: ['█   █', '█   █', '█████', '    █', '    █'],
  5: ['█████', '█    ', '█████', '    █', '█████'],
  6: ['█████', '█    ', '█████', '█   █', '█████'],
  7: ['█████', '    █', '   █ ', '  █  ', '  █  '],
  8: ['█████', '█   █', '█████', '█   █', '█████'],
  9: ['█████', '█   █', '█████', '    █', '█████'],
  ':': ['   ', ' █ ', '   ', ' █ ', '   '],
};

function bigText(text) {
  const rows = [0, 1, 2, 3, 4].map((r) => '    ' + text.split('').map((c) => DIGITS[c][r]).join('  '));
  return rows.join('\n');
}

function banner() {
  return [
    '',
    '==================================================================',
    '  브라우저에서 실제 학생 계정으로 로그인해 두세요 (예: 20100123)',
    '  카운트다운이 끝나는 순간 [수강신청 진입]을 누르면 됩니다',
    `  부하 테스트 학생 ${STUDENTS}명도 그 순간 한꺼번에 대기열에 들어갑니다`,
    '==================================================================',
    '',
  ].join('\n');
}

export function setup() {
  const now = Date.now();
  const startAt = START_AT_MS || (START_AT ? parseStartAt(START_AT) : now + COUNTDOWN_S * 1000);
  if (startAt < now + 3000) {
    throw new Error(`START_AT ${START_AT}이 이미 지났거나 너무 가깝습니다.`);
  }
  console.log(`OPEN_AT_MS=${startAt}`);
  console.log(`가상 학생 ${STUDENTS}명 준비 완료 (모두 로그인된 상태)`);
  console.log(banner());
  return { startAt };
}

function countdown(startAt) {
  for (;;) {
    const left = Math.round((startAt - Date.now()) / 1000);
    if (left <= 0) {
      console.log([
        '',
        '##################################################################',
        '##                                                              ##',
        '##            지금 누르세요!   [수강신청 진입]                  ##',
        '##                                                              ##',
        '##################################################################',
        '',
      ].join('\n'));
      return;
    }
    if (left <= 5) {
      console.log(`\n${bigText(String(left))}\n\n    ${left}초 뒤에 수강신청 진입을 누르세요\n`);
    } else if (left % 5 === 0) {
      console.log(`오픈까지 ${left}초`);
    }
    sleepUntil(startAt - (left - 1) * 1000);
  }
}

function sleepUntil(epochMs) {
  const wait = epochMs - Date.now();
  if (wait > 0) sleep(wait / 1000);
}

function enterQueue(params, startAt) {
  for (;;) {
    const res = http.post(`${BASE}/queue`, '{}', params('enter_queue'));
    if (res.status !== 400 || Date.now() > startAt + OPEN_RETRY_S * 1000) return res;
    sleep(0.2);
  }
}

function json(res) {
  try {
    return res.json();
  } catch (e) {
    return {};
  }
}

function pickOfferings(offerings) {
  const groups = [[], [], [], [], [], []];
  for (const o of offerings) {
    if (!o.courseId.startsWith('LT')) continue;
    groups[(Number(o.courseId.substring(2)) - 1) % 6].push(o);
  }
  const picks = [];
  for (const group of groups) {
    if (group.length === 0) continue;
    group.sort((a, b) => (a.courseId + a.section).localeCompare(b.courseId + b.section));
    picks.push(group[Math.floor(group.length * Math.pow(Math.random(), SKEW))]);
  }
  for (let i = picks.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [picks[i], picks[j]] = [picks[j], picks[i]];
  }
  return picks.slice(0, PICKS);
}

export default function (data) {
  const index = exec.vu.idInTest - 1;
  const token = TOKENS[index];
  if (index === 0) countdown(data.startAt);
  const params = (name) => ({
    headers: { 'content-type': 'application/json', authorization: `Bearer ${token}` },
    tags: { name },
    timeout: '60s',
    responseCallback: name === 'enter_queue' ? ENTER_EXPECTED : undefined,
  });

  sleepUntil(data.startAt + Math.random() * ARRIVAL_SPREAD_S * 1000);

  let res;
  if (!PREADMITTED) {
    res = enterQueue(params, data.startAt);
    let status = json(res);
    let errors = 0;
    while (status.state !== 'ADMITTED') {
      sleep((status.nextPollMs || 2000) / 1000);
      res = http.get(`${BASE}/queue`, params('queue_poll'));
      queuePolls.add(1);
      if (res.status !== 200) {
        errors++;
        if (errors > 20) {
          gaveUp.add(1);
          return;
        }
        status = {};
        continue;
      }
      status = json(res);
      if (status.state === 'NOT_IN_QUEUE') {
        res = enterQueue(params, data.startAt);
        status = json(res);
      }
    }
  }
  const admittedAt = Date.now();
  admitted.add(1);
  timeToAdmit.add(Date.now() - data.startAt);

  res = http.get(`${BASE}/offerings`, params('offerings'));
  const offerings = json(res).offerings || [];
  if (offerings.length === 0) {
    gaveUp.add(1);
    return;
  }

  for (const offering of pickOfferings(offerings)) {
    sleep(0.3 + Math.random() * 0.5);
    res = http.post(`${BASE}/enrollments`, JSON.stringify({ offeringId: offering.offeringId }), params('enroll'));
    const body = json(res);
    const failed = res.status !== 200;
    enrollErrorRate.add(failed);
    if (failed) {
      enrollError.add(1, { reason: String(res.status) });
    } else if (body.success) {
      enrollSuccess.add(1);
      lastSuccessAt.add(Date.now() - data.startAt);
    } else if (body.message === FULL) {
      enrollFull.add(1);
    } else {
      enrollRejected.add(1);
    }
  }

  sleepUntil(admittedAt + SESSION_S * 1000);
  sessionTime.add(Date.now() - admittedAt);
  res = http.del(`${BASE}/queue`, null, params('leave_queue'));
  if (res.status === 200 && json(res).released) {
    released.add(1);
  } else {
    releaseFailed.add(1, { status: String(res.status) });
  }
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

export function handleSummary(data) {
  const startAt = data.setup_data ? data.setup_data.startAt : 0;
  const success = metric(data, 'enroll_success', 'count');
  const full = metric(data, 'enroll_full', 'count');
  const rejected = metric(data, 'enroll_rejected', 'count');
  const error = metric(data, 'enroll_error', 'count');
  const attempts = success + full + rejected + error;
  const lastAdmit = metric(data, 'time_to_admit', 'max');
  const lastSuccess = metric(data, 'enroll_success_offset', 'max');
  const lat = (name) => {
    const key = `http_req_duration{name:${name}}`;
    return `p95 ${ms(metric(data, key, 'p(95)'))}, p99 ${ms(metric(data, key, 'p(99)'))}, 최대 ${ms(metric(data, key, 'max'))}`;
  };
  const failedThresholds = Object.entries(data.metrics)
    .flatMap(([name, m]) => Object.entries(m.thresholds || {}).filter(([, t]) => !t.ok).map(([expr]) => `${name} ${expr}`));

  const lines = [
    '',
    `================ 8/11 수강신청 재현 결과 ${MODE} ================`,
    `  학생 수                 ${STUDENTS}명 (모두 로그인된 상태에서 시작)`,
    `  대기열 입장 완료        ${metric(data, 'students_admitted', 'count')}명, 중도 포기 ${metric(data, 'students_gave_up', 'count')}명`,
    `  마지막 학생 입장까지    ${ms(lastAdmit)} (오픈 기준)`,
    `  입장 대기 시간          중앙값 ${ms(metric(data, 'time_to_admit', 'med'))}, p95 ${ms(metric(data, 'time_to_admit', 'p(95)'))}`,
    `  대기열 조회 요청        ${metric(data, 'queue_polls', 'count')}회`,
    `  수강신청 시도           ${attempts}건`,
    `    성공                  ${success}건`,
    `    정원 마감             ${full}건`,
    `    기타 거절             ${rejected}건 (시간 중복, 학점 초과 등)`,
    `    오류                  ${error}건 (오류율 ${(attempts ? (error / attempts) * 100 : 0).toFixed(2)}%)`,
    `  전체 HTTP 요청          ${metric(data, 'http_reqs', 'count')}건, 평균 ${metric(data, 'http_reqs', 'rate').toFixed(1)} req/s, 실패율 ${(metric(data, 'http_req_failed', 'rate') * 100).toFixed(2)}%`,
    `  대기열 진입             ${lat('enter_queue')}`,
    `  대기열 조회             ${lat('queue_poll')}`,
    `  강의 목록               ${lat('offerings')}`,
    `  수강신청                ${lat('enroll')}`,
    `  입장권 반납             ${lat('leave_queue')} (반납 ${metric(data, 'passes_released', 'count')}명, 실패 ${metric(data, 'release_failed', 'count')}명)`,
    `  입장 후 머문 시간       중앙값 ${ms(metric(data, 'session_time', 'med'))}, p95 ${ms(metric(data, 'session_time', 'p(95)'))}`,
    `  마지막 신청 성공 시각   오픈 후 ${ms(lastSuccess)}`,
    `  임계값                  ${failedThresholds.length === 0 ? '모두 통과' : '실패: ' + failedThresholds.join(', ')}`,
    '=========================================================',
    '',
  ];
  const stamp = new Date(startAt || Date.now()).toISOString().replace(/[:.]/g, '-');
  const env = [
    `K6_MODE=${MODE}`,
    `K6_STUDENTS=${STUDENTS}`,
    `K6_ADMITTED=${metric(data, 'students_admitted', 'count')}`,
    `K6_ATTEMPTS=${attempts}`,
    `K6_SUCCESS=${success}`,
    `K6_FULL=${full}`,
    `K6_ERRORS=${error}`,
    `K6_ERROR_PCT=${(attempts ? (error / attempts) * 100 : 0).toFixed(2)}`,
    `K6_HTTP_FAILED_PCT=${(metric(data, 'http_req_failed', 'rate') * 100).toFixed(2)}`,
    `K6_RPS=${metric(data, 'http_reqs', 'rate').toFixed(1)}`,
    `K6_ENROLL_P95=${Math.round(metric(data, 'http_req_duration{name:enroll}', 'p(95)'))}`,
    `K6_ENROLL_P99=${Math.round(metric(data, 'http_req_duration{name:enroll}', 'p(99)'))}`,
    `K6_POLL_P95=${Math.round(metric(data, 'http_req_duration{name:queue_poll}', 'p(95)'))}`,
    `K6_POLL_P99=${Math.round(metric(data, 'http_req_duration{name:queue_poll}', 'p(99)'))}`,
    `K6_LAST_ADMIT_MS=${Math.round(lastAdmit)}`,
    `K6_START_AT_MS=${startAt}`,
    `K6_LAST_SUCCESS_AT_MS=${Math.round(startAt + lastSuccess)}`,
    '',
  ].join('\n');
  return {
    stdout: lines.join('\n'),
    [`${SUMMARY_DIR}/loadtest-0811-${stamp}.json`]: JSON.stringify(data, null, 2),
    [`${SUMMARY_DIR}/loadtest-0811-${stamp}.txt`]: lines.join('\n'),
    [LAST_RUN_FILE]: env,
  };
}
