import { writeFileSync } from 'node:fs';

const BASE = process.env.BASE_URL || 'http://192.168.64.30/api';
const USER_ID = process.env.USER_ID || '60220435';
const PASSWORD = process.env.PASSWORD || USER_ID;
const CLICK_DELAY_MS = Number(process.env.CLICK_DELAY_MS || 450);
const STAY_MS = Number(process.env.STAY_MS || 3000);
const OUT = process.env.OUT || 'presenter.env';
const GIVE_UP_MS = Number(process.env.GIVE_UP_MS || 1800000);

const sleep = (ms) => new Promise((r) => setTimeout(r, Math.max(0, ms)));

async function call(method, path, token, body) {
  const t0 = Date.now();
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(60000),
    });
    const text = await res.text();
    let json = {};
    try { json = JSON.parse(text); } catch {}
    return { status: res.status, json, ms: Date.now() - t0 };
  } catch (e) {
    return { status: 0, json: {}, ms: Date.now() - t0, error: String(e) };
  }
}

const login = await call('POST', '/login', null, { userId: USER_ID, password: PASSWORD });
if (!login.json.token) {
  console.error(`presenter login failed ${login.status}`);
  process.exit(1);
}
const token = login.json.token;
console.log(`presenter ${USER_ID} logged in (${login.ms}ms), waiting for open time`);

let openAt = 0;
let offset = 0;
const deadline = Date.now() + GIVE_UP_MS;
while (Date.now() < deadline) {
  const s = await call('GET', '/schedule', token);
  const open = Number(s.json.openAtMs || 0);
  const now = Number(s.json.serverNowMs || 0);
  if (s.status === 200 && open > 0 && now > 0 && open - now < 60000 && open - now > -5000) {
    offset = now + s.ms / 2 - Date.now();
    openAt = open;
    break;
  }
  await sleep(200);
}
if (!openAt) {
  console.error('presenter never saw an open time');
  process.exit(1);
}
const local = (serverMs) => serverMs - offset;
const rel = (localMs) => Math.round(localMs + offset - openAt);
console.log(`presenter sees open in ${Math.round(openAt - (Date.now() + offset))}ms, clock offset ${Math.round(offset)}ms`);

await sleep(local(openAt + CLICK_DELAY_MS) - Date.now());
const clickAt = Date.now();
let res = await call('POST', '/queue', token, {});
while (res.status === 400 && Date.now() - clickAt < 15000) {
  await sleep(200);
  res = await call('POST', '/queue', token, {});
}
const enterMs = res.ms;
let firstPosition = res.json.position ?? '';
let polls = 0;
let pollErrors = 0;
let maxPollMs = 0;
let lastPosition = firstPosition;
while (res.json.state !== 'ADMITTED' && Date.now() < deadline) {
  await sleep(res.json.nextPollMs || 2000);
  res = await call('GET', '/queue', token);
  polls++;
  maxPollMs = Math.max(maxPollMs, res.ms);
  if (res.status !== 200) {
    pollErrors++;
    res = { json: {} };
    continue;
  }
  if (res.json.state === 'WAITING') lastPosition = res.json.position;
  if (res.json.state === 'NOT_IN_QUEUE') res = await call('POST', '/queue', token, {});
}
const screenAt = Date.now();
const offerings = await call('GET', '/offerings', token);
const listedAt = Date.now();
await sleep(STAY_MS);
const leave = await call('DELETE', '/queue', token);

const out = {
  PRESENTER_ID: USER_ID,
  PRESENTER_CLICK_AFTER_OPEN_MS: rel(clickAt),
  PRESENTER_ENTER_MS: enterMs,
  PRESENTER_FIRST_POSITION: firstPosition,
  PRESENTER_POLLS: polls,
  PRESENTER_POLL_ERRORS: pollErrors,
  PRESENTER_MAX_POLL_MS: maxPollMs,
  PRESENTER_SCREEN_AFTER_OPEN_MS: rel(screenAt),
  PRESENTER_SCREEN_AFTER_CLICK_MS: screenAt - clickAt,
  PRESENTER_OFFERINGS_STATUS: offerings.status,
  PRESENTER_OFFERINGS_MS: offerings.ms,
  PRESENTER_LIST_AFTER_OPEN_MS: rel(listedAt),
  PRESENTER_LEAVE_STATUS: leave.status,
  PRESENTER_LEAVE_RELEASED: leave.json.released === true,
};
writeFileSync(OUT, Object.entries(out).map(([k, v]) => `${k}=${v}`).join('\n') + '\n');
console.log(Object.entries(out).map(([k, v]) => `${k}=${v}`).join(' '));
