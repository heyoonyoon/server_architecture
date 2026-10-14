const fs = require('fs');
const path = require('path');
const pptxgen = require('pptxgenjs');
const sharp = require('sharp');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const { FaServer, FaCogs } = require('react-icons/fa');

const ICON_DIR = process.argv[2] || path.join(__dirname, 'icons');
const OUT = process.argv[3] || path.join(__dirname, 'architecture.pptx');
const FONT = 'Apple SD Gothic Neo';

const BR = { redis: 'DC382D', apachekafka: '231F20', postgresql: '4169E1', nginx: '009639', envoyproxy: 'AC6199', react: '61DAFB', ubuntu: 'E95420', docker: '2496ED', grafana: 'F46800', prometheus: 'E6522C', googlechrome: '4285F4' };
const COL = { rest: '2563EB', grpc: '16A34A', redis: 'DC382D', kafka: '111827', jdbc: '7C3AED', mon: '94A3B8', reply: '64748B' };
const INK = '1F2937', SUB = '475569', MUTED = '64748B';

const pres = new pptxgen();
pres.layout = 'LAYOUT_WIDE';
pres.title = '수강신청 시스템 인프라 아키텍처';
pres.theme = { headFontFace: FONT, bodyFontFace: FONT };

async function png(svg) {
  const buf = await sharp(Buffer.from(svg), { density: 300 }).resize(512, 512, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toBuffer();
  return 'image/png;base64,' + buf.toString('base64');
}
async function brandIcon(name) {
  const svg = fs.readFileSync(path.join(ICON_DIR, name + '.svg'), 'utf8').replace('<svg ', `<svg fill="#${BR[name]}" width="512" height="512" `);
  return png(svg);
}
async function reactIcon(Comp, color) {
  const svg = renderToStaticMarkup(React.createElement(Comp, { size: 512, color: '#' + color })).replace(/currentColor/g, '#' + color);
  return png(svg.includes('xmlns=') ? svg : svg.replace('<svg ', '<svg xmlns="http://www.w3.org/2000/svg" '));
}

function text(s, name, t, x, y, w, h, o = {}) {
  s.addText(t, Object.assign({ x, y, w, h, fontFace: FONT, fontSize: 10, color: INK, align: 'center', valign: 'top', margin: 0, isTextBox: true, objectName: name }, o));
}
function title(s, t) {
  text(s, 'title', t, 0.3, 0.22, 9, 0.45, { fontSize: 22, bold: true, color: '0F172A', align: 'left', valign: 'middle' });
}
function img(s, name, data, x, y, w, h = w) {
  s.addImage({ data, x, y, w, h, objectName: name });
}
function badge(s, name, cx, cy, kind, num, d = 0.25) {
  s.addText(String(num), { shape: pres.shapes.OVAL, x: cx - d / 2, y: cy - d / 2, w: d, h: d, fill: { color: COL[kind] }, line: { color: 'FFFFFF', width: 1.25 }, fontFace: FONT, fontSize: 10, bold: true, color: 'FFFFFF', align: 'center', valign: 'middle', margin: 0, objectName: name });
}
function route(s, name, pts, color, o = {}) {
  const line = { color, width: o.width || 1.75, dashType: o.dash ? 'dash' : 'solid' };
  if (o.arrow !== false) line.endArrowType = 'triangle';
  if (pts.length === 2) {
    const [[x1, y1], [x2, y2]] = pts;
    s.addShape(pres.shapes.LINE, { x: Math.min(x1, x2), y: Math.min(y1, y2), w: Math.abs(x2 - x1), h: Math.abs(y2 - y1), flipH: x2 < x1, flipV: y2 < y1, line, objectName: name });
    return;
  }
  const xs = pts.map(p => p[0]), ys = pts.map(p => p[1]);
  const x0 = Math.min(...xs), y0 = Math.min(...ys);
  s.addShape(pres.shapes.CUSTOM_GEOMETRY, { x: x0, y: y0, w: Math.max(...xs) - x0, h: Math.max(...ys) - y0, points: pts.map(([x, y]) => ({ x: x - x0, y: y - y0 })), line, objectName: name });
}
function vm(s, key, x, y, w, h, ip, ubuntu) {
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.12, fill: { color: 'F8FAFC' }, line: { color: '94A3B8', width: 1.25, dashType: 'dash' }, objectName: `vm-${key}` });
  img(s, `vm-${key}-ubuntu`, ubuntu, x + 0.12, y + 0.09, 0.2);
  text(s, `vm-${key}-title`, [{ text: key, options: { bold: true, fontSize: 11, color: '0F172A' } }, { text: '   ' + ip, options: { fontSize: 10, color: MUTED } }], x + 0.38, y + 0.07, w - 0.5, 0.24, { align: 'left', valign: 'middle' });
}
function node(s, key, data, cx, y, size, ttl, lines, tw = 1.9) {
  img(s, `icon-${key}`, data, cx - size / 2, y, size);
  text(s, `label-${key}`, ttl, cx - tw / 2, y + size + 0.04, tw, 0.22, { fontSize: 11, bold: true });
  if (lines.length) text(s, `desc-${key}`, lines.join('\n'), cx - tw / 2, y + size + 0.26, tw, 0.19 * lines.length, { color: SUB });
}

async function slideArchitecture(I) {
  const s = pres.addSlide();
  s.background = { color: 'FFFFFF' };
  title(s, '수강신청 시스템 인프라 아키텍처');

  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.3, y: 2.0, w: 1.35, h: 1.65, rectRadius: 0.12, fill: { color: 'F0F9FF' }, line: { color: '38BDF8', width: 1.25 }, objectName: 'group-laptop' });
  text(s, 'group-laptop-title', '노트북 (macOS)', 0.3, 2.08, 1.35, 0.24, { fontSize: 11, bold: true, color: '0C4A6E' });
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.62, y: 2.42, w: 0.71, h: 0.46, rectRadius: 0.04, fill: { color: 'E0F2FE' }, line: { color: '0369A1', width: 1.5 }, objectName: 'laptop-screen' });
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.52, y: 2.88, w: 0.91, h: 0.07, rectRadius: 0.03, fill: { color: '0369A1' }, line: { color: '0369A1', width: 0.5 }, objectName: 'laptop-base' });
  img(s, 'icon-chrome', I.googlechrome, 0.835, 2.51, 0.28);
  text(s, 'label-browser', '브라우저', 0.3, 3.02, 1.35, 0.22, { fontSize: 11, bold: true });
  text(s, 'desc-browser', '화면 실행, REST 요청', 0.3, 3.24, 1.35, 0.19, { color: SUB });

  vm(s, 'sa-lb', 1.95, 1.6, 2.1, 3.65, '192.168.64.30', I.ubuntu);
  img(s, 'icon-nginx', I.nginx, 2.42, 2.05, 0.5);
  img(s, 'icon-react', I.react, 3.08, 2.05, 0.5);
  text(s, 'label-nginx', 'nginx :80', 2.0, 2.59, 2.0, 0.22, { fontSize: 11, bold: true });
  text(s, 'desc-nginx', 'React 빌드 파일(HTML, JS) 제공\n/api/* 요청은 Envoy로 전달', 1.97, 2.81, 2.06, 0.38, { color: SUB, fontSize: 9.5 });
  node(s, 'envoy', I.envoyproxy, 3.0, 3.8, 0.5, 'Envoy :8080', ['REST/JSON → gRPC 변환', '라운드로빈, 타임아웃, CORS'], 2.0);

  vm(s, 'sa-app1', 4.6, 1.0, 2.2, 2.15, '192.168.64.31', I.ubuntu);
  node(s, 'app1', I.server, 5.7, 1.4, 0.5, 'gRPC 서버 (Java)', ['JWT 인증, 권한, 접근 로그', '로그인, 학생/과목 관리', '대기열, 수강신청 API'], 2.0);
  img(s, 'icon-app1-docker', I.docker, 6.45, 2.84, 0.25);
  vm(s, 'sa-app2', 4.6, 3.55, 2.2, 2.15, '192.168.64.32', I.ubuntu);
  node(s, 'app2', I.server, 5.7, 3.95, 0.5, 'gRPC 서버 (Java)', ['app1과 같은 코드'], 2.0);
  img(s, 'icon-app2-docker', I.docker, 6.45, 5.37, 0.25);

  vm(s, 'sa-infra', 7.78, 1.0, 5.25, 2.95, '192.168.64.34', I.ubuntu);
  node(s, 'redis', I.redis, 8.56, 1.4, 0.45, 'Redis', ['대기열, 입장권', '남은 자리, 내 신청 목록'], 1.5);
  node(s, 'kafka', I.apachekafka, 8.56, 2.6, 0.45, 'Kafka', ['enrollment-events', '신청/취소 이벤트'], 1.5);
  node(s, 'queue-worker', I.gear, 10.35, 1.4, 0.45, 'queue-worker (1개)', ['0.5초마다 입장 처리'], 1.9);
  node(s, 'enrollment-consumer', I.gear, 10.35, 2.6, 0.45, 'enrollment-consumer (1개)', ['Kafka → DB 저장'], 2.0);
  s.addShape(pres.shapes.LINE, { x: 11.45, y: 1.1, w: 0, h: 2.75, line: { color: 'CBD5E1', width: 1 }, objectName: 'infra-monitoring-divider' });
  text(s, 'label-monitoring', '모니터링', 11.64, 1.07, 1.2, 0.22, { bold: true, color: MUTED });
  node(s, 'prometheus', I.prometheus, 12.24, 1.38, 0.42, 'Prometheus', ['5초마다 지표 수집'], 1.4);
  node(s, 'grafana', I.grafana, 12.24, 2.6, 0.42, 'Grafana', ['그래프 대시보드'], 1.4);

  vm(s, 'sa-db', 7.78, 4.25, 5.25, 1.45, '192.168.64.33', I.ubuntu);
  img(s, 'icon-postgresql', I.postgresql, 10.075, 4.6, 0.55);
  text(s, 'label-postgresql', 'PostgreSQL', 10.8, 4.5, 2.1, 0.22, { fontSize: 11, bold: true, align: 'left' });
  text(s, 'desc-postgresql', '최종 장부\nusers, student, professor\ncourse, course_offering\nenrollment', 10.8, 4.72, 2.1, 0.76, { color: SUB, align: 'left' });

  route(s, 'arrow-1-browser-nginx', [[1.65, 2.3], [2.39, 2.3]], COL.rest);
  badge(s, 'badge-1', 1.8, 2.3, 'rest', 1);
  route(s, 'arrow-2-nginx-envoy', [[3.0, 3.27], [3.0, 3.76]], COL.rest);
  badge(s, 'badge-2', 3.0, 3.515, 'rest', 2);
  route(s, 'arrow-3-envoy-app1', [[3.27, 3.92], [4.35, 3.92], [4.35, 1.65], [5.4, 1.65]], COL.grpc);
  badge(s, 'badge-3-app1', 4.35, 2.8, 'grpc', 3);
  route(s, 'arrow-3-envoy-app2', [[3.27, 4.2], [5.4, 4.2]], COL.grpc);
  badge(s, 'badge-3-app2', 4.33, 4.2, 'grpc', 3);

  const X = { e: 6.98, b: 7.14, a: 7.30, c: 7.46, d: 7.62 }, SX = 5.98, RL = 8.3, PL = 10.04;
  route(s, 'arrow-4-7-app1-redis', [[SX, 1.47], [RL, 1.47]], COL.redis);
  route(s, 'arrow-8-app1-kafka', [[SX, 1.65], [X.a, 1.65], [X.a, 2.7], [RL, 2.7]], COL.kafka);
  route(s, 'arrow-6-app1-postgresql', [[SX, 1.83], [X.b, 1.83], [X.b, 4.72], [PL, 4.72]], COL.jdbc);
  route(s, 'arrow-4-7-app2-redis', [[SX, 4.02], [X.c, 4.02], [X.c, 1.75], [RL, 1.75]], COL.redis);
  route(s, 'arrow-8-app2-kafka', [[SX, 4.2], [X.d, 4.2], [X.d, 2.95], [RL, 2.95]], COL.kafka);
  route(s, 'arrow-6-app2-postgresql', [[SX, 4.38], [X.e, 4.38], [X.e, 5.0], [PL, 5.0]], COL.jdbc);
  for (const [app, y] of [['app1', 1.47], ['app2', 4.02]]) {
    badge(s, `badge-4-${app}`, 6.13, y, 'redis', 4);
    badge(s, `badge-7-${app}`, 6.39, y, 'redis', 7);
    badge(s, `badge-8-${app}`, 6.62, y + 0.18, 'kafka', 8);
    badge(s, `badge-6-${app}`, 6.84, y + 0.36, 'jdbc', 6);
  }

  route(s, 'arrow-5-worker-redis', [[10.1, 1.62], [8.81, 1.62]], COL.redis);
  badge(s, 'badge-5', 9.45, 1.62, 'redis', 5);
  route(s, 'arrow-9-kafka-consumer', [[8.81, 2.82], [10.1, 2.82]], COL.kafka);
  badge(s, 'badge-9', 9.45, 2.82, 'kafka', 9);
  route(s, 'arrow-10-consumer-postgresql', [[10.35, 3.58], [10.35, 4.57]], COL.jdbc);
  badge(s, 'badge-10', 10.35, 4.1, 'jdbc', 10);
  route(s, 'arrow-11-prometheus-scrape', [[12.48, 1.59], [12.85, 1.59], [12.85, 0.82], [3.0, 0.82], [3.0, 1.57]], COL.mon, { dash: true });
  badge(s, 'badge-11', 9.45, 0.82, 'mon', 11);

  const leg = [
    ['1', '브라우저 → nginx: React 화면 파일 받기, REST/JSON 요청 (토큰)', 'rest'],
    ['2', 'nginx → Envoy: /api 떼고 전달', 'rest'],
    ['3', 'Envoy → 서버 2대: gRPC 라운드로빈', 'grpc'],
    ['4', '서버 → Redis: 대기열 진입, 순번 조회', 'redis'],
    ['5', 'queue-worker → Redis: 빈자리만큼 입장권 발급', 'redis'],
    ['6', '서버 → PostgreSQL: 로그인, 관리, 선수과목 조회 (JDBC)', 'jdbc'],
    ['7', '서버 → Redis: 입장권 확인, 자리 차감(Lua)', 'redis'],
    ['8', '서버 → Kafka: 신청/취소 이벤트 (저장 확인 후 응답)', 'kafka'],
    ['9', 'Kafka → enrollment-consumer: 이벤트 전달', 'kafka'],
    ['10', 'enrollment-consumer → PostgreSQL: 멱등 저장 (ON CONFLICT)', 'jdbc'],
    ['11', 'Prometheus: Envoy, nginx, DB, Redis 지표 수집', 'mon'],
  ];
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: 0.3, y: 5.85, w: 12.73, h: 1.5, rectRadius: 0.08, fill: { color: 'F8FAFC' }, line: { color: 'E2E8F0', width: 1 }, objectName: 'legend-box' });
  leg.forEach(([n, t, k], i) => {
    const x0 = 0.55 + Math.floor(i / 6) * 6.35, cy = 6.06 + (i % 6) * 0.226;
    badge(s, `legend-badge-${n}`, x0, cy, k, n, 0.21);
    text(s, `legend-text-${n}`, t, x0 + 0.2, cy - 0.11, 5.9, 0.22, { align: 'left', valign: 'middle', color: '334155' });
  });
}

function est(t, pt) {
  let em = 0;
  for (const ch of t) {
    if (/[가-힣]/.test(ch)) em += 0.95;
    else if (ch === ' ') em += 0.3;
    else if (/[A-Z]/.test(ch)) em += 0.66;
    else if (/[a-z0-9]/.test(ch)) em += 0.56;
    else if (ch === '→') em += 0.9;
    else em += 0.4;
  }
  return em * pt / 72;
}

function sequence(s, key, parts, items) {
  const P = Object.fromEntries(parts.map(p => [p.id, p]));
  const SLIDE_R = 13.03, STEP = 0.34, LPT = 10.5;
  let y = 1.8, n = 0;
  const stack = [], frames = [], notes = [], msgs = [];
  for (const it of items) {
    if (it.t === 'msg' || it.t === 'self') { n += 1; msgs.push(Object.assign({ y, n }, it)); y += it.t === 'self' ? 0.42 : STEP; }
    else if (it.t === 'loop' || it.t === 'alt') { y += 0.12; stack.push({ kind: it.t, label: it.label, x1: it.x1, x2: it.x2, top: y - 0.3, elses: [] }); y += 0.32; }
    else if (it.t === 'else') { stack[stack.length - 1].elses.push({ y: y - 0.2, label: it.label }); y += 0.3; }
    else if (it.t === 'end') { const f = stack.pop(); f.bottom = y - 0.2; frames.push(f); y += 0.12; }
    else if (it.t === 'note') { y += 0.08; notes.push(Object.assign({ y }, it)); y += 0.5; }
  }
  const lifeEnd = msgs[msgs.length - 1].y + 0.25;
  parts.forEach(p => {
    s.addShape(pres.shapes.LINE, { x: p.x, y: 1.4, w: 0, h: lifeEnd - 1.4, line: { color: '94A3B8', width: 1, dashType: 'dash' }, objectName: `lifeline-${p.id}` });
    s.addText(p.label, { shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.06, x: p.x - p.w / 2, y: 0.95, w: p.w, h: 0.45, fill: { color: 'F1F5F9' }, line: { color: '64748B', width: 1 }, fontFace: FONT, fontSize: 11, bold: true, color: '0F172A', align: 'center', valign: 'middle', margin: 0, objectName: `participant-${p.id}` });
  });
  frames.forEach((f, i) => {
    const nm = `${key}-${f.kind}-${i + 1}`;
    s.addShape(pres.shapes.RECTANGLE, { x: f.x1, y: f.top, w: f.x2 - f.x1, h: f.bottom - f.top, line: { color: '64748B', width: 1 }, objectName: nm });
    s.addText(f.kind, { shape: pres.shapes.RECTANGLE, x: f.x1, y: f.top, w: 0.5, h: 0.22, fill: { color: 'E2E8F0' }, line: { color: '64748B', width: 1 }, fontFace: FONT, fontSize: 10, bold: true, color: '0F172A', align: 'center', valign: 'middle', margin: 0, objectName: `${nm}-tag` });
    text(s, `${nm}-condition`, `[${f.label}]`, f.x1 + 0.6, f.top, est(f.label, 10) + 0.4, 0.22, { bold: true, color: '334155', align: 'left', valign: 'middle', fill: { color: 'FFFFFF' } });
    f.elses.forEach((e, j) => {
      s.addShape(pres.shapes.LINE, { x: f.x1, y: e.y, w: f.x2 - f.x1, h: 0, line: { color: '64748B', width: 1, dashType: 'dash' }, objectName: `${nm}-else-${j + 1}` });
      text(s, `${nm}-else-${j + 1}-condition`, `[${e.label}]`, f.x1 + 0.6, e.y + 0.02, est(e.label, 10) + 0.4, 0.22, { bold: true, color: '334155', align: 'left', valign: 'middle', fill: { color: 'FFFFFF' } });
    });
  });
  notes.forEach((nt, i) => {
    const x1 = P[nt.from].x - 0.4, x2 = P[nt.to].x + 0.4;
    s.addText(nt.text, { shape: pres.shapes.RECTANGLE, x: x1, y: nt.y - 0.25, w: x2 - x1, h: 0.38, fill: { color: 'FEF9C3' }, line: { color: 'CA8A04', width: 1 }, fontFace: FONT, fontSize: 10.5, color: '422006', align: 'center', valign: 'middle', margin: 0, objectName: `${key}-note-${i + 1}` });
  });
  msgs.forEach(m => {
    const nm = `${key}-msg-${m.n}`, color = COL[m.kind];
    const fx = P[m.from].x;
    if (m.t === 'self') {
      route(s, nm, [[fx, m.y - 0.08], [fx + 0.4, m.y - 0.08], [fx + 0.4, m.y + 0.14], [fx + 0.03, m.y + 0.14]], color, { width: 1.5 });
      text(s, `${nm}-label`, m.text, fx + 0.5, m.y - 0.08, est(m.text, LPT) + 0.3, 0.22, { fontSize: LPT, align: 'left', valign: 'middle', color: INK });
      return;
    }
    const tx = P[m.to].x;
    route(s, nm, [[fx, m.y], [tx > fx ? tx - 0.02 : tx + 0.02, m.y]], color, { width: 1.5, dash: m.kind === 'reply' });
    const w = est(m.text, LPT) + 0.2, lo = Math.min(fx, tx), hi = Math.max(fx, tx);
    let lx = (fx + tx) / 2 - w / 2;
    if (w > hi - lo - 0.3) lx = Math.max(lo + 0.18, Math.min(lx, SLIDE_R - w));
    text(s, `${nm}-label`, m.text, lx, m.y - 0.3, w, 0.2, { fontSize: LPT, color: INK, fill: { color: 'FFFFFF' }, valign: 'middle' });
  });
  msgs.forEach(m => badge(s, `${key}-msg-${m.n}-badge`, P[m.from].x, m.t === 'self' ? m.y - 0.08 : m.y, m.kind, m.n, 0.21));
}

function slideQueue() {
  const s = pres.addSlide();
  s.background = { color: 'FFFFFF' };
  title(s, '대기열 진입 → 입장');
  const parts = [
    { id: 'B', label: '브라우저', x: 1.0, w: 1.2 },
    { id: 'E', label: 'Envoy', x: 3.3, w: 1.2 },
    { id: 'S', label: 'gRPC 서버 (app1/app2)', x: 5.8, w: 2.1 },
    { id: 'R', label: 'Redis', x: 8.6, w: 1.2 },
    { id: 'W', label: 'queue-worker', x: 11.8, w: 1.6 },
  ];
  sequence(s, 'queue', parts, [
    { t: 'msg', from: 'B', to: 'E', text: 'POST /api/queue (토큰)', kind: 'rest' },
    { t: 'msg', from: 'E', to: 'S', text: 'EnterQueue (gRPC)', kind: 'grpc' },
    { t: 'msg', from: 'S', to: 'R', text: 'ZADD queue NX 학번 진입시각', kind: 'redis' },
    { t: 'msg', from: 'S', to: 'B', text: '"앞에 120명, 2초 뒤 다시 조회"', kind: 'reply' },
    { t: 'loop', label: '0.5초마다', x1: 8.0, x2: 12.75 },
    { t: 'msg', from: 'W', to: 'R', text: '만료된 입장권 정리', kind: 'redis' },
    { t: 'msg', from: 'W', to: 'R', text: '빈자리 = 한도 - 입장 인원', kind: 'redis' },
    { t: 'msg', from: 'W', to: 'R', text: 'ZPOPMIN queue 빈자리만큼 → 입장권 발급(5분)', kind: 'redis' },
    { t: 'end' },
    { t: 'loop', label: '2초마다', x1: 0.5, x2: 9.2 },
    { t: 'msg', from: 'B', to: 'E', text: 'GET /api/queue', kind: 'rest' },
    { t: 'msg', from: 'E', to: 'S', text: 'GetQueueStatus', kind: 'grpc' },
    { t: 'msg', from: 'S', to: 'R', text: '입장권 있어? 없으면 ZRANK', kind: 'redis' },
    { t: 'msg', from: 'S', to: 'B', text: '"앞에 N명" 또는 "입장"', kind: 'reply' },
    { t: 'end' },
    { t: 'self', from: 'B', text: '수강신청 화면으로 이동', kind: 'rest' },
  ]);
}

function slideEnroll() {
  const s = pres.addSlide();
  s.background = { color: 'FFFFFF' };
  title(s, '수강신청 처리');
  const parts = [
    { id: 'B', label: '브라우저', x: 0.9, w: 1.2 },
    { id: 'S', label: 'gRPC 서버 (app1/app2)', x: 4.35, w: 2.1 },
    { id: 'R', label: 'Redis', x: 6.9, w: 1.1 },
    { id: 'DB', label: 'PostgreSQL', x: 8.7, w: 1.3 },
    { id: 'K', label: 'Kafka', x: 10.3, w: 1.1 },
    { id: 'C', label: 'enrollment-consumer', x: 12.15, w: 1.7 },
  ];
  sequence(s, 'enroll', parts, [
    { t: 'msg', from: 'B', to: 'S', text: 'POST /api/enrollments {offeringId} (Envoy 경유)', kind: 'rest' },
    { t: 'msg', from: 'S', to: 'R', text: '입장권 확인 (+ 유효시간 연장)', kind: 'redis' },
    { t: 'msg', from: 'S', to: 'DB', text: '선수과목 이수 여부 조회', kind: 'jdbc' },
    { t: 'msg', from: 'S', to: 'R', text: '내 신청 목록 → 시간 충돌, 학점 한도 검사', kind: 'redis' },
    { t: 'msg', from: 'S', to: 'R', text: 'Lua 한 번: 중복? → 자리 남음? → 자리 -1, 내 목록 추가', kind: 'redis' },
    { t: 'alt', label: '마감 / 중복 / 검사 실패', x1: 0.45, x2: 10.8 },
    { t: 'msg', from: 'S', to: 'B', text: '실패 사유', kind: 'reply' },
    { t: 'else', label: '자리 확보' },
    { t: 'msg', from: 'S', to: 'K', text: 'ENROLLED 이벤트 발행', kind: 'kafka' },
    { t: 'msg', from: 'K', to: 'S', text: '저장 완료 (acks=all)', kind: 'reply' },
    { t: 'msg', from: 'S', to: 'B', text: '신청 완료', kind: 'reply' },
    { t: 'end' },
    { t: 'note', from: 'S', to: 'K', text: 'Kafka 발행 실패 시 Redis 자리와 목록을 되돌리고 실패 응답' },
    { t: 'msg', from: 'K', to: 'C', text: 'ENROLLED 이벤트 전달', kind: 'kafka' },
    { t: 'msg', from: 'C', to: 'DB', text: 'INSERT ... ON CONFLICT DO NOTHING', kind: 'jdbc' },
    { t: 'msg', from: 'C', to: 'K', text: '처리 위치(offset) 확정', kind: 'kafka' },
  ]);
}

(async () => {
  const I = {};
  for (const k of Object.keys(BR)) I[k] = await brandIcon(k);
  I.server = await reactIcon(FaServer, '334155');
  I.gear = await reactIcon(FaCogs, 'B45309');
  await slideArchitecture(I);
  slideQueue();
  slideEnroll();
  await pres.writeFile({ fileName: OUT });
  console.log(OUT);
})();
