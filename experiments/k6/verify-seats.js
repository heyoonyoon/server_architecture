import grpc from 'k6/net/grpc';
import { sleep } from 'k6';

const TARGET = __ENV.TARGET || 'localhost:8080';
const DATA_ADDR = __ENV.DATA_ADDR || 'localhost:9200';
const PROTO_DIR = __ENV.PROTO_DIR || '../../Proto/src/main/proto';
const SEMESTER = __ENV.SEMESTER || '2026-2';
const FIRST_ID = Number(__ENV.FIRST_ID || 30000001);
const STUDENTS = Number(__ENV.STUDENTS || 3660);
const PASSWORD = __ENV.PASSWORD || 'loadtest0811';
const CHECK_STUDENT = __ENV.CHECK_STUDENT || String(FIRST_ID);
const OFFERING_MIN = Number(__ENV.OFFERING_MIN || 101);
const OFFERING_MAX = Number(__ENV.OFFERING_MAX || 110);
const EXPECTED = Number(__ENV.EXPECTED || -1);
const BASELINE = Number(__ENV.BASELINE || 0);
const DRAIN_TIMEOUT_S = Number(__ENV.DRAIN_TIMEOUT_S || 180);
const ADMIT_TIMEOUT_S = Number(__ENV.ADMIT_TIMEOUT_S || 120);
const PHASE = __ENV.PHASE || 'post';
const SUMMARY_DIR = __ENV.SUMMARY_DIR || '.';
const DATA = 'serverarchitecture.data.DataService/';
const ENROLLMENT = 'serverarchitecture.EnrollmentService/';

const data = new grpc.Client();
data.load([PROTO_DIR], 'data.proto');
const front = new grpc.Client();
front.load([PROTO_DIR], 'auth.proto', 'enrollment.proto');

export const options = {
  setupTimeout: `${DRAIN_TIMEOUT_S + ADMIT_TIMEOUT_S + 60}s`,
  vus: 1,
  iterations: 1,
};

function isLoadStudent(id) {
  const n = Number(id);
  return n >= FIRST_ID && n < FIRST_ID + STUDENTS;
}

function inLoadSections(id) {
  const n = Number(id);
  return n >= OFFERING_MIN && n <= OFFERING_MAX;
}

function readEnrolled() {
  const res = data.invoke(`${DATA}ReadWhere`, { collection: 'enrollments', field: 'status', value: 'ENROLLED' });
  if (res.status !== grpc.StatusOK) throw new Error(`Data ReadWhere 실패: ${res.status}`);
  const perOffering = {};
  let load = 0;
  let loadSections = 0;
  for (const item of res.message.items || []) {
    const f = item.fields || {};
    if (f.semester !== SEMESTER) continue;
    perOffering[f.offering_id] = (perOffering[f.offering_id] || 0) + 1;
    if (isLoadStudent(f.student_id)) load++;
    if (inLoadSections(f.offering_id)) loadSections++;
  }
  return { perOffering, load, loadSections };
}

function readCapacity() {
  const res = data.invoke(`${DATA}ReadAll`, { collection: 'offerings' });
  if (res.status !== grpc.StatusOK) throw new Error(`Data ReadAll 실패: ${res.status}`);
  const capacity = {};
  for (const item of res.message.items || []) {
    const f = item.fields || {};
    if (f.semester === SEMESTER) capacity[item.id] = Number(f.capacity || 0);
  }
  return capacity;
}

function drain() {
  const started = Date.now();
  let enrolled = readEnrolled();
  if (EXPECTED < 0) return { enrolled, drainMs: -1 };
  const target = BASELINE + EXPECTED;
  let previous = -1;
  let stable = 0;
  while (enrolled.load !== target && Date.now() - started < DRAIN_TIMEOUT_S * 1000) {
    stable = enrolled.load === previous ? stable + 1 : 0;
    if (stable >= 15) break;
    previous = enrolled.load;
    sleep(1);
    enrolled = readEnrolled();
  }
  return { enrolled, drainMs: enrolled.load === target ? Date.now() - started : -1 };
}

function redisRemaining() {
  front.connect(TARGET, { plaintext: true, timeout: '10s' });
  const login = front.invoke('serverarchitecture.AuthService/Login', { user_id: CHECK_STUDENT, password: PASSWORD });
  if (login.status !== grpc.StatusOK) return null;
  const params = { metadata: { authorization: `Bearer ${login.message.token}` } };
  let status = front.invoke(`${ENROLLMENT}EnterQueue`, {}, params);
  const deadline = Date.now() + ADMIT_TIMEOUT_S * 1000;
  while (status.status === grpc.StatusOK && status.message.state !== 'ADMITTED' && Date.now() < deadline) {
    sleep(1);
    status = front.invoke(`${ENROLLMENT}GetQueueStatus`, {}, params);
  }
  if (status.status !== grpc.StatusOK || status.message.state !== 'ADMITTED') return null;
  const list = front.invoke(`${ENROLLMENT}ListOfferings`, {}, params);
  front.invoke(`${ENROLLMENT}LeaveQueue`, {}, params);
  front.close();
  if (list.status !== grpc.StatusOK) return null;
  const remaining = {};
  for (const o of list.message.offerings || []) {
    if (o.semester === SEMESTER) remaining[String(o.offeringId)] = o.remaining;
  }
  return remaining;
}

export function setup() {
  data.connect(DATA_ADDR, { plaintext: true, timeout: '10s' });
  const capacity = readCapacity();
  const { enrolled, drainMs } = PHASE === 'pre' ? { enrolled: readEnrolled(), drainMs: -1 } : drain();
  let overCapacity = 0;
  let fullSections = 0;
  for (const [id, count] of Object.entries(enrolled.perOffering)) {
    if (capacity[id] === undefined) continue;
    if (count > capacity[id]) overCapacity++;
    if (count === capacity[id]) fullSections++;
  }
  const loadSeats = Object.entries(capacity)
    .filter(([id]) => inLoadSections(id))
    .reduce((a, [, c]) => a + c, 0);
  const loadDetail = Object.keys(capacity)
    .filter(inLoadSections)
    .sort((a, b) => Number(a) - Number(b))
    .map((id) => `${id}:${enrolled.perOffering[id] || 0}/${capacity[id]}`)
    .join(' ');
  const total = Object.values(enrolled.perOffering).reduce((a, b) => a + b, 0);
  data.close();

  let redisChecked = 0;
  let redisMismatch = -1;
  let redisNegative = -1;
  if (PHASE !== 'pre') {
    const remaining = redisRemaining();
    if (remaining) {
      redisChecked = Object.keys(remaining).length;
      redisMismatch = 0;
      redisNegative = 0;
      for (const [id, left] of Object.entries(remaining)) {
        if (capacity[id] === undefined) continue;
        if (left < 0) redisNegative++;
        if (left + (enrolled.perOffering[id] || 0) !== capacity[id]) redisMismatch++;
      }
    }
  }
  return {
    phase: PHASE,
    sections: Object.keys(capacity).length,
    total,
    load: enrolled.load,
    expected: EXPECTED,
    baseline: BASELINE,
    drainMs,
    overCapacity,
    fullSections,
    loadSeats,
    loadSections: enrolled.loadSections,
    loadDetail,
    redisChecked,
    redisMismatch,
    redisNegative,
  };
}

export default function () {}

export function handleSummary(summary) {
  const r = summary.setup_data || {};
  const lines = [
    '',
    `================ 정원, 저장 정합성 확인 (${r.phase}) ================`,
    `  ${SEMESTER} 개설 강의         ${r.sections}개`,
    `  Data ENROLLED 전체        ${r.total}건 (부하 학생 ${r.load}건)`,
    `  k6 성공 + 실행 전 기준    ${r.expected < 0 ? '비교 안 함' : `${r.expected} + ${r.baseline} = ${r.expected + r.baseline}건`}`,
    `  Kafka 반영 완료까지       ${r.drainMs < 0 ? '일치하지 않음 또는 비교 안 함' : `${(r.drainMs / 1000).toFixed(1)}s (확인 시작 기준)`}`,
    `  정원 초과 강의            ${r.overCapacity}개`,
    `  정원이 꽉 찬 강의         ${r.fullSections}개`,
    `  부하 분반 ${OFFERING_MIN}~${OFFERING_MAX}       신청 ${r.loadSections}건 / 좌석 ${r.loadSeats}석${r.loadSections > r.loadSeats ? ' (초과!)' : ''}`,
    `  분반별 신청/정원          ${r.loadDetail}`,
    `  Redis 좌석 불일치 강의    ${r.redisChecked ? `${r.redisMismatch}개 (남은 좌석 + Data 신청 수 = 정원, ${r.redisChecked}개 비교)` : '확인 못 함'}`,
    `  Redis 남은 좌석 음수      ${r.redisChecked ? `${r.redisNegative}개` : '확인 못 함'}`,
    '==================================================================',
    '',
  ].join('\n');
  const env = [
    `V_PHASE=${r.phase}`,
    `V_SECTIONS=${r.sections}`,
    `V_DATA_TOTAL=${r.total}`,
    `V_DATA_LOAD=${r.load}`,
    `V_EXPECTED=${r.expected}`,
    `V_BASELINE=${r.baseline}`,
    `V_DRAIN_MS=${r.drainMs}`,
    `V_OVER_CAPACITY=${r.overCapacity}`,
    `V_FULL_SECTIONS=${r.fullSections}`,
    `V_LOAD_SECTION_ENROLLED=${r.loadSections}`,
    `V_LOAD_SECTION_SEATS=${r.loadSeats}`,
    `V_REDIS_CHECKED=${r.redisChecked}`,
    `V_REDIS_MISMATCH=${r.redisMismatch}`,
    `V_REDIS_NEGATIVE=${r.redisNegative}`,
    '',
  ].join('\n');
  return {
    stdout: lines,
    [`${SUMMARY_DIR}/verify-${r.phase}.txt`]: lines,
    [`${SUMMARY_DIR}/verify-${r.phase}.env`]: env,
  };
}
