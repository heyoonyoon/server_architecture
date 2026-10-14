const BASE = process.env.BASE_URL ?? 'http://192.168.64.30/api';
const ENVOY_ADMIN = process.env.ENVOY_ADMIN ?? 'http://192.168.64.30:9901';
const COURSE = process.env.COURSE_ID ?? '17653';
const SECTION = process.env.SECTION ?? '02';
const RESULT_FILE = process.env.RESULT_FILE;

async function call(method, path, token, body) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let json = {};
  try {
    json = JSON.parse(text);
  } catch {
    json = { message: text };
  }
  return { status: response.status, json };
}

async function login(id, password = id) {
  const { status, json } = await call('POST', '/login', null, { userId: id, password });
  if (status !== 200 || !json.token) {
    throw new Error(`${id} 로그인 실패: ${status} ${json.message ?? ''}`);
  }
  return json.token;
}

async function envoyCounters() {
  const text = await (await fetch(`${ENVOY_ADMIN}/clusters`)).text();
  const counters = {};
  for (const line of text.split('\n')) {
    const match = line.match(/^grpc_servers::([\d.]+):\d+::rq_total::(\d+)$/);
    if (match) counters[match[1]] = Number(match[2]);
  }
  return counters;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const professor = await login('P001');
const { json: list } = await call('GET', '/students', professor);
const students = list.students
  .filter((s) => !s.department.startsWith('LT-'))
  .map((s) => s.studentId);
console.log(`학생 ${students.length}명 로그인 중 (bcrypt라 몇 초 걸립니다)`);

const tokens = await Promise.all(students.map((id) => login(id)));
console.log('로그인 완료, 모두 대기열에 들어갑니다');

await Promise.all(tokens.map((t) => call('POST', '/queue', t, {})));
for (;;) {
  const states = await Promise.all(tokens.map((t) => call('GET', '/queue', t)));
  const admitted = states.filter((s) => s.json.state === 'ADMITTED').length;
  process.stdout.write(`\r입장권 받은 학생 ${admitted}/${tokens.length}   `);
  if (admitted === tokens.length) break;
  await sleep(states[0].json.nextPollMs ?? 1000);
}
console.log();

const { json: offeringList } = await call('GET', '/offerings', tokens[0]);
const offering = offeringList.offerings.find((o) => o.courseId === COURSE && o.section === SECTION);
if (!offering) {
  throw new Error(`${COURSE}-${SECTION} 강의를 찾을 수 없습니다`);
}
console.log(`대상 강의 ${offering.courseName} ${COURSE}-${SECTION} (offering ${offering.offeringId}), 정원 ${offering.capacity}, 남은 좌석 ${offering.remaining}`);
console.log(`${tokens.length}명이 동시에 신청 버튼을 누릅니다`);

const before = await envoyCounters();
const started = performance.now();
const results = await Promise.all(
  tokens.map((t, i) =>
    call('POST', '/enrollments', t, { offeringId: offering.offeringId })
      .then((r) => ({ id: students[i], ...r, ms: performance.now() - started }))
      .catch((e) => ({ id: students[i], status: 0, json: { message: e.message }, ms: performance.now() - started })),
  ),
);
const elapsed = performance.now() - started;
const after = await envoyCounters();

const FULL = '정원이 마감되었습니다.';
const success = results.filter((r) => r.status === 200 && r.json.success);
const full = results.filter((r) => r.status === 200 && !r.json.success && r.json.message === FULL);
const other = results.filter((r) => !success.includes(r) && !full.includes(r));

console.log();
for (const r of results.sort((a, b) => a.ms - b.ms)) {
  const label = success.includes(r) ? '성공' : full.includes(r) ? '마감' : '오류';
  console.log(`  ${r.id}  ${label}  ${r.ms.toFixed(0).padStart(4)}ms  ${r.json.message ?? ''}`);
}
console.log();
console.log('==================== 동시 신청 결과 ====================');
console.log(`  정원           ${offering.capacity}`);
console.log(`  성공           ${success.length}`);
console.log(`  정원 마감      ${full.length}`);
console.log(`  오류           ${other.length}`);
console.log(`  걸린 시간      ${elapsed.toFixed(0)}ms (17건 모두 응답받을 때까지)`);
const split = Object.keys(after)
  .map((host) => `${host} ${after[host] - (before[host] ?? 0)}건`)
  .join(', ');
console.log(`  서버별 분배    ${split} (Envoy 카운터 차이, 같은 시간대 다른 요청 포함 가능)`);
console.log(`  성공한 학생    ${success.map((r) => r.id).join(', ')}`);
console.log('========================================================');

if (RESULT_FILE) {
  const { writeFileSync } = await import('node:fs');
  writeFileSync(RESULT_FILE, `${offering.offeringId} ${success.length} ${offering.capacity}\n`);
}
process.exit(other.length === 0 && success.length <= offering.capacity ? 0 : 1);
