import grpc from 'k6/net/grpc';

const DATA_ADDR = __ENV.DATA_ADDR || 'localhost:9200';
const PROTO_DIR = __ENV.PROTO_DIR || '../../Proto/src/main/proto';
const SEMESTER = __ENV.SEMESTER || '2026-2';
const FIRST_ID = Number(__ENV.FIRST_ID || 30000001);
const STUDENTS = Number(__ENV.STUDENTS || 3660);
const OFFERING_MIN = Number(__ENV.OFFERING_MIN || 101);
const OFFERING_MAX = Number(__ENV.OFFERING_MAX || 110);
const SUMMARY_DIR = __ENV.SUMMARY_DIR || '.';
const DATA = 'serverarchitecture.data.DataService/';

const data = new grpc.Client();
data.load([PROTO_DIR], 'data.proto');

export const options = {
  setupTimeout: '600s',
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

function call(method, request) {
  const res = data.invoke(`${DATA}${method}`, request);
  if (res.status !== grpc.StatusOK) throw new Error(`Data ${method} 실패: ${res.status} ${res.error && res.error.message}`);
  return res.message;
}

export function setup() {
  data.connect(DATA_ADDR, { plaintext: true, timeout: '30s' });
  const capacity = {};
  for (const item of call('ReadAll', { collection: 'offerings' }).items || []) {
    const f = item.fields || {};
    if (f.semester === SEMESTER && inLoadSections(item.id)) capacity[item.id] = Number(f.capacity || 0);
  }

  let deleted = 0;
  const kept = {};
  for (const item of call('ReadAll', { collection: 'enrollments' }).items || []) {
    const f = item.fields || {};
    if (isLoadStudent(f.student_id)) {
      if (call('Delete', { collection: 'enrollments', id: item.id }).deleted) deleted++;
    } else if (f.semester === SEMESTER && f.status === 'ENROLLED' && capacity[f.offering_id] !== undefined) {
      kept[f.offering_id] = (kept[f.offering_id] || 0) + 1;
    }
  }

  let remaining = 0;
  for (const item of call('ReadAll', { collection: 'enrollments' }).items || []) {
    if (isLoadStudent((item.fields || {}).student_id)) remaining++;
  }
  data.close();

  const seats = Object.keys(capacity)
    .sort((a, b) => Number(a) - Number(b))
    .map((id) => `${id} ${capacity[id] - (kept[id] || 0)}`);
  return { deleted, remaining, seats };
}

export default function () {}

export function handleSummary(summary) {
  const r = summary.setup_data || { deleted: -1, remaining: -1, seats: [] };
  const text = `DELETED=${r.deleted}\nREMAINING=${r.remaining}\n`;
  return {
    stdout: text + r.seats.map((s) => `SEAT ${s}`).join('\n') + '\n',
    [`${SUMMARY_DIR}/seats.txt`]: r.seats.join('\n') + '\n',
  };
}
