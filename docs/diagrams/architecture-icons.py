import re, sys, os, math
OUT = sys.argv[1]
ICON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'icons')
def path(name): return re.search(r' d="([^"]+)"', open(f'{ICON_DIR}/{name}.svg').read()).group(1)
BR = {'redis':'#DC382D','apachekafka':'#231F20','postgresql':'#4169E1','nginx':'#009639','envoyproxy':'#AC6199','react':'#61DAFB','ubuntu':'#E95420','docker':'#2496ED','grafana':'#F46800','prometheus':'#E6522C','googlechrome':'#4285F4'}
el = []; ar = []
F = "font-family:'Apple SD Gothic Neo','Noto Sans KR',sans-serif"
def icon(name, x, y, s=56):
    el.append(f'<g transform="translate({x},{y}) scale({s/24})"><path d="{path(name)}" fill="{BR[name]}"/></g>')
def text(x, y, t, size=15, w='normal', c='#1f2937', a='middle'):
    el.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{w}" fill="{c}" text-anchor="{a}" style="{F}">{t}</text>')
def vm(x, y, w, h, title, ip):
    el.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="#f8fafc" stroke="#94a3b8" stroke-width="1.6" stroke-dasharray="6 4"/>')
    icon('ubuntu', x+12, y+10, 22); text(x+42, y+28, title, 15, 'bold', '#0f172a', 'start'); text(x+w-14, y+28, ip, 13, 'normal', '#64748b', 'end')
def subs(cx, y, lines):
    for i, l in enumerate(lines): text(cx, y+i*18, l, 12.5, 'normal', '#475569')
def node(cx, y, ic, title, lines=(), s=56):
    icon(ic, cx-s/2, y, s); text(cx, y+s+22, title, 15, 'bold'); subs(cx, y+s+42, lines)
def server_icon(cx, y, c='#334155'):
    x = cx-26
    for i in range(3):
        el.append(f'<rect x="{x}" y="{y+i*19}" width="52" height="16" rx="3" fill="#e2e8f0" stroke="{c}" stroke-width="1.6"/><circle cx="{x+9}" cy="{y+i*19+8}" r="2.4" fill="#22c55e"/><line x1="{x+18}" y1="{y+i*19+8}" x2="{x+44}" y2="{y+i*19+8}" stroke="{c}" stroke-width="1.6"/>')
def gear_icon(cx, y):
    el.append(f'<circle cx="{cx}" cy="{y+28}" r="22" fill="#fef3c7" stroke="#b45309" stroke-width="2"/><circle cx="{cx}" cy="{y+28}" r="8" fill="#b45309"/>')
    for a in range(0, 360, 45):
        r = math.radians(a)
        el.append(f'<line x1="{cx+22*math.cos(r):.1f}" y1="{y+28+22*math.sin(r):.1f}" x2="{cx+30*math.cos(r):.1f}" y2="{y+28+30*math.sin(r):.1f}" stroke="#b45309" stroke-width="5" stroke-linecap="round"/>')
COL = {'rest':'#2563eb','grpc':'#16a34a','redis':'#DC382D','kafka':'#111827','jdbc':'#7c3aed','mon':'#94a3b8'}
def arrow(pts, kind, nums=(), seg=0, dash=False):
    d = 'M ' + ' L '.join(f'{x},{y}' for x, y in pts); c = COL[kind]
    da = ' stroke-dasharray="7 5"' if dash else ''
    ar.append(f'<path d="{d}" fill="none" stroke="{c}" stroke-width="2.4"{da} stroke-linejoin="round" marker-end="url(#m-{kind})"/>')
    (x1, y1), (x2, y2) = pts[seg], pts[seg+1]; L = math.hypot(x2-x1, y2-y1)
    spots = sorted(((y1+y2)/2+(y2-y1)/L*o, (x1+x2)/2+(x2-x1)/L*o) for o in ((i-(len(nums)-1)/2)*28 for i in range(len(nums))))
    for num, (my, mx) in zip(nums, spots):
        ar.append(f'<circle cx="{mx}" cy="{my}" r="13" fill="{c}" stroke="white" stroke-width="2"/><text x="{mx}" y="{my+5}" font-size="14" font-weight="bold" fill="white" text-anchor="middle" style="{F}">{num}</text>')
W, H = 1800, 1180
el.append('<rect x="30" y="380" width="210" height="200" rx="14" fill="#f0f9ff" stroke="#38bdf8" stroke-width="1.6"/>')
text(135, 412, '노트북 (macOS)', 15, 'bold', '#0c4a6e')
el.append('<rect x="95" y="440" width="80" height="52" rx="5" fill="#e0f2fe" stroke="#0369a1" stroke-width="2"/><rect x="83" y="492" width="104" height="8" rx="3" fill="#0369a1"/>'); icon('googlechrome', 120, 451, 30)
text(135, 535, '브라우저', 15, 'bold'); text(135, 555, '화면 실행, REST 요청', 12.5, 'normal', '#475569')
vm(300, 270, 330, 470, 'sa-lb', '192.168.64.30')
icon('nginx', 409, 320, 56); icon('react', 465, 320, 56); text(465, 398, 'nginx :80', 15, 'bold'); subs(465, 418, ['React 빌드 파일(HTML, JS) 제공', '/api/* 요청은 Envoy로 전달'])
node(465, 520, 'envoyproxy', 'Envoy :8080', ['REST/JSON → gRPC 변환', '라운드로빈, 타임아웃, CORS'])
vm(720, 110, 330, 300, 'sa-app1', '192.168.64.31'); server_icon(885, 170); text(885, 260, 'gRPC 서버 (Java)', 15, 'bold'); subs(885, 282, ['JWT 인증, 권한, 접근 로그', '로그인, 학생/과목 관리', '대기열, 수강신청 API']); icon('docker', 1005, 365, 28)
vm(720, 620, 330, 300, 'sa-app2', '192.168.64.32'); server_icon(885, 680); text(885, 770, 'gRPC 서버 (Java)', 15, 'bold'); subs(885, 792, ['app1과 같은 코드']); icon('docker', 1005, 875, 28)
vm(1180, 60, 590, 640, 'sa-infra', '192.168.64.34')
node(1330, 110, 'redis', 'Redis', ['대기열, 입장권', '남은 자리, 내 신청 목록'])
node(1620, 110, 'apachekafka', 'Kafka', ['enrollment-events', '신청/취소 이벤트'])
gear_icon(1330, 320); text(1330, 408, 'queue-worker (1개)', 15, 'bold'); subs(1330, 428, ['0.5초마다 입장 처리'])
gear_icon(1620, 320); text(1620, 408, 'enrollment-consumer (1개)', 15, 'bold'); subs(1620, 428, ['Kafka → DB 저장'])
el.append('<line x1="1200" y1="480" x2="1750" y2="480" stroke="#cbd5e1" stroke-width="1.2"/>'); text(1250, 505, '모니터링', 13, 'bold', '#64748b')
node(1330, 520, 'prometheus', 'Prometheus', ['5초마다 지표 수집'], 48)
node(1500, 520, 'grafana', 'Grafana', ['그래프 대시보드'], 48)
vm(1180, 750, 590, 300, 'sa-db', '192.168.64.33')
node(1475, 800, 'postgresql', 'PostgreSQL', ['최종 장부', 'users, student, professor, course', 'course_offering, enrollment'], 64)
arrow([(240, 480), (403, 360)], 'rest', ('1',))
arrow([(465, 446), (465, 515)], 'rest', ('2',))
arrow([(525, 565), (670, 565), (670, 190), (830, 190)], 'grpc', ('3',), 1)
arrow([(525, 585), (670, 585), (670, 700), (830, 700)], 'grpc', ('3',), 1)
arrow([(945, 220), (1120, 220), (1120, 135), (1290, 135)], 'redis', ('4', '7'), 2)
arrow([(945, 720), (1140, 720), (1140, 160), (1290, 160)], 'redis', ('4', '7'), 1)
arrow([(945, 250), (1090, 250), (1090, 30), (1620, 30), (1620, 100)], 'kafka', ('8',), 2)
arrow([(945, 745), (1100, 745), (1100, 30)], 'kafka')
arrow([(945, 320), (1060, 320), (1060, 880), (1400, 880)], 'jdbc', ('6',), 2)
arrow([(945, 830), (1040, 830), (1040, 905), (1400, 905)], 'jdbc', ('6',), 1)
arrow([(1330, 315), (1330, 240)], 'redis', ('5',))
arrow([(1620, 240), (1620, 315)], 'kafka', ('9',))
arrow([(1690, 440), (1740, 440), (1740, 880), (1550, 880)], 'jdbc', ('10',), 1)
arrow([(1290, 545), (630, 545)], 'mon', ('11',), 0, True)
lx, ly = 40, 1000
leg = [('1','브라우저 → nginx: React 화면 파일 받기, REST/JSON 요청 (토큰)','rest'),('2','nginx → Envoy: /api 떼고 전달','rest'),('3','Envoy → 서버 2대: gRPC 라운드로빈','grpc'),('4','서버 → Redis: 대기열 진입, 순번 조회','redis'),('5','queue-worker → Redis: 빈자리만큼 입장권 발급','redis'),('6','서버 → PostgreSQL: 로그인, 관리, 선수과목 조회 (JDBC)','jdbc'),
       ('7','서버 → Redis: 입장권 확인, 자리 차감(Lua)','redis'),('8','서버 → Kafka: 신청/취소 이벤트 (저장 확인 후 응답)','kafka'),('9','Kafka → enrollment-consumer: 이벤트 전달','kafka'),('10','enrollment-consumer → PostgreSQL: 멱등 저장 (ON CONFLICT)','jdbc'),('11','Prometheus: Envoy, nginx, DB, Redis 지표 수집','mon')]
el.append(f'<rect x="{lx-14}" y="{ly-26}" width="1110" height="164" rx="10" fill="#f8fafc" stroke="#e2e8f0"/>')
for i, (n, t, k) in enumerate(leg):
    cx = lx + (i//6)*550; cy = ly + (i % 6)*24
    el.append(f'<circle cx="{cx+10}" cy="{cy-4}" r="9" fill="{COL[k]}"/><text x="{cx+10}" y="{cy}" font-size="10.5" fill="white" text-anchor="middle" font-weight="bold" style="{F}">{n}</text>')
    text(cx+28, cy, t, 13, 'normal', '#334155', 'start')
text(1770, 1160, '수강신청 시스템 인프라 아키텍처', 13, 'bold', '#94a3b8', 'end')
defs = ''.join(f'<marker id="m-{k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{c}"/></marker>' for k, c in COL.items())
svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}"><defs>{defs}</defs><rect width="{W}" height="{H}" fill="white"/>' + ''.join(el) + ''.join(ar) + '</svg>'
open(os.path.join(OUT, 'architecture-icons.svg'), 'w').write(svg)
open(os.path.join(OUT, 'architecture-icons.html'), 'w').write(f'<html><body style="margin:0">{svg}</body></html>')
