import { createHmac } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const STUDENTS = Number(process.env.STUDENTS ?? 3660);
const FIRST_ID = Number(process.env.FIRST_ID ?? 30000001);
const HOURS = Number(process.env.TOKEN_HOURS ?? 12);
const OUT = process.env.TOKENS_FILE ?? '.tokens.json';
const BASE = process.env.BASE_URL ?? 'http://192.168.64.30/api';

function readSecret() {
  if (process.env.JWT_SECRET) return process.env.JWT_SECRET;
  const line = execFileSync('ssh', ['sa-app1', 'grep ^JWT_SECRET= ~/sa/deploy/app/.env'], { encoding: 'utf8' }).trim();
  const secret = line.slice('JWT_SECRET='.length).replace(/^["']|["']$/g, '');
  if (!secret) throw new Error('sa-app1의 deploy/app/.env에서 JWT_SECRET을 찾지 못했습니다');
  return secret;
}

const encode = (value) => Buffer.from(value).toString('base64url');
const header = encode('{"alg":"HS256","typ":"JWT"}');
const secret = readSecret();
const now = Math.floor(Date.now() / 1000);
const exp = now + HOURS * 3600;

function token(n) {
  const payload = JSON.stringify({
    sub: String(FIRST_ID + n),
    name: `0811학생${String(n + 1).padStart(4, '0')}`,
    role: 'STUDENT',
    iat: now,
    exp,
  });
  const content = `${header}.${encode(payload)}`;
  return `${content}.${createHmac('sha256', secret).update(content).digest('base64url')}`;
}

const tokens = Array.from({ length: STUDENTS }, (_, n) => token(n));

if (process.env.SKIP_SERVER_CHECK !== '1') {
  const check = await fetch(`${BASE}/schedule`, { headers: { authorization: `Bearer ${tokens[0]}` } });
  if (check.status !== 200) {
    console.error(`서버가 생성한 토큰을 받아들이지 않습니다 (HTTP ${check.status}). JWT_SECRET이 맞는지 확인하세요.`);
    process.exit(1);
  }
}

writeFileSync(OUT, JSON.stringify({ createdAt: Date.now(), expiresAt: exp * 1000, firstId: FIRST_ID, tokens }));
console.log(`부하 테스트 학생 ${STUDENTS}명 토큰 생성 완료 (${OUT}, ${HOURS}시간 유효${process.env.SKIP_SERVER_CHECK === '1' ? '' : ', 서버 검증 통과'})`);
