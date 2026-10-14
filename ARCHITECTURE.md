# 수강신청 시스템 아키텍처

> 대용량 트래픽(수강신청 오픈 순간의 폭주)을 가정한 3계층 gRPC 수강신청 시스템의 설계 문서.
> 결정 과정과 근거의 시간순 기록은 [REPORT_LOG.md](REPORT_LOG.md), 개별 고민과 해결은 [DEV_NOTES.md](DEV_NOTES.md)에 있다.

## 1. 목표와 범위

### 기능 요구사항
| 기능 | 설명 |
|---|---|
| 학생 관리 | 학생 목록 조회, 추가, 삭제 |
| 과목 관리 | 과목 목록 조회, 추가, 삭제 |
| 수강신청 | 정원 내 신청, 중복 신청 방지, 선수과목 검사 |
| 인증 | 학생 로그인 (JWT), 관리자 권한 구분 |
| 로깅 | 모든 요청의 구조화 로그, 요청 추적 |

### 품질 요구사항
- **폭주 대응**: 명지대학교 2026년 8/11 수강신청 대상(인문대학 1,748명 + 사회과학대학 1,347명 + 인공지능·소프트웨어융합대학 565명 = 3,660명, 2026.04.01 재학생 기준)이 10:00 정각 1~3초 안에 동시에 접속하는 상황을 견딘다.
- **정확성**: 정원 초과 신청 0건, 같은 과목 중복 신청 0건.
- **장애 격리**: 서버 한 대가 멈춰도 서비스가 유지되고, 사용자가 수동으로 새로고침하지 않아도 회복된다.
- **재현성**: `docker compose up` 한 번으로 전체 시스템을 실행할 수 있다.

### 구현 원칙
- 과제가 요구하는 부분(gRPC 서버, 클라이언트)만 직접 구현한다. 그 외는 현업에서 쓰는 기성 도구를 쓴다.
- 기술 스택은 현업과 달라도 되지만, 구성 요소와 역할 분담은 현업과 같게 한다.
- 기술 선택 기준: 요구사항 적합성 → 필요 기능 → 성숙도와 지원 → 운영 비용. 점유율은 보조 신호로만 본다.

## 2. 전체 구성

```
 노트북 (macOS)
 ├ [브라우저: React + TypeScript 앱]      ← 사용자
 └ [k6 부하 생성기]
        │
        │ http://192.168.64.20      (A: 단일 출처, nginx 경유)
        │ http://192.168.64.20:8080 (B: 데이터는 Envoy 직접, CORS)
        ▼
┌────────────── sugang-server VM (192.168.64.20) ──────────────┐
│  [nginx :80]  프론트 빌드 파일 제공, /api → Envoy (운영에서는 CDN 자리) │
│  [Envoy :8080] gRPC-Web → gRPC 변환, 로드밸런싱, 타임아웃, CORS  │
│         ┌──────────┴──────────┐                              │
│   [server1 :8081]       [server2 :8081]   (stateless)        │
│   [queue-worker]  [enrollment-consumer]   (이후 단계)          │
└───────┬───────────────────────────────┬──────────────────────┘
        │ JDBC                          │ Redis / Kafka
        ▼                               ▼
┌── sugang-db VM (192.168.64.21) ──┐  ┌── sugang-infra VM (192.168.64.22) ──┐
│ [PostgreSQL :5432]               │  │ [Redis :6379]  [Kafka :9092]        │
│ [postgres-exporter :9187]        │  │ [Prometheus :9090] [Grafana :3000]  │
└──────────────────────────────────┘  └─────────────────────────────────────┘
```

- 클라이언트 VM은 두지 않는다. 사용자는 노트북 브라우저로 접속하고, 부하 생성기도 노트북에서 돌린다.
- DB와 Redis/Kafka를 다른 VM에 둔다. Kafka 실험에서 DB만 저사양으로 제한하므로, 같은 VM에 두면 자원 경합 때문에 원인을 구분할 수 없다. 장애도 함께 번진다. 현업에서도 DB, 캐시, 메시지 큐는 따로 운영한다.
- 모니터링은 측정 대상(server, db)과 떨어진 infra VM에 둔다.

### 구성 요소
| 구성 요소 | 역할 | 기술 | 직접 구현 |
|---|---|---|---|
| 웹 클라이언트 | 로그인, 대기 화면, 수강신청, 관리 화면 | React + TypeScript, proto에서 생성한 gRPC-Web 클라이언트 | O |
| nginx | 정적 파일(React 빌드 결과) 제공 | nginx | 설정만 |
| Envoy | gRPC-Web ↔ gRPC 변환, 로드밸런싱, 헬스체크 | Envoy | 설정만 |
| gRPC 서버 ×N | 모든 비즈니스 로직 (stateless) | Java + gRPC | O |
| queue-worker | 빈자리만큼 대기열에서 입장 처리 | Java | O |
| enrollment-consumer | Kafka의 신청 이벤트를 DB에 저장 | Java | O |
| Redis | 대기열, 입장권, 정원, 캐시, 요청 제한 | Redis | 설정만 |
| Kafka | 수강신청 확정 이벤트 로그 | Kafka | 설정만 |
| PostgreSQL | 최종 저장소 | PostgreSQL | 스키마만 |
| Prometheus + Grafana | 지표 수집, 시각화 | Prometheus, Grafana, 각종 exporter | 설정만 |
| 부하 생성기 | 8/11 트래픽 재현 | k6 (gRPC 지원), 보조로 ghz | 시나리오 스크립트 |

## 3. 계층 구조와 통신 방식

### 3계층: 클라이언트 → 서버 → 데이터
- 데이터 계층은 **PostgreSQL 자체**다. 서버는 JDBC로 DB에 직접 접속한다.
- 처음에는 DB 앞에 별도의 Data gRPC 서버를 두었다. DB로 바꾸면서 제거했다. DB는 이미 네트워크로 접속하는 서버이므로 그 앞에 gRPC 서버를 두면 홉만 늘어난다. 반대로 저장소가 txt 같은 파일이면 파일을 혼자 책임지는 Data 서버가 필요하다 (여러 서버가 동시에 같은 파일을 쓰면 깨진다).

### 통신 방식
| 구간 | 프로토콜 | 이유 |
|---|---|---|
| 브라우저 → Envoy | gRPC-Web | 브라우저는 HTTP/2 트레일러를 다룰 수 없어 gRPC를 직접 쓸 수 없다 |
| Envoy → 서버 | gRPC | 서버 코드를 바꾸지 않는다. 같은 proto로 서버와 프론트의 타입을 함께 생성한다 |
| 서버 → PostgreSQL | JDBC (커넥션 풀) | DB 전용 프로토콜. 현업에서 서버와 DB 사이에 gRPC를 쓰지 않는다 |
| 서버 → Redis, Kafka | 각 전용 프로토콜 | |

- nginx가 아니라 Envoy를 쓰는 이유: nginx는 gRPC 프록시(`grpc_pass`)는 지원하지만 gRPC-Web 변환 기능은 기본으로 없다. Envoy는 gRPC-Web 필터를 기본 제공하고, gRPC-Web 공식 문서가 기본 프록시로 안내한다.
- 현업 비교: 외부 클라이언트와 서버 사이는 REST/JSON이 가장 흔하고, gRPC는 주로 서비스 간 통신에 쓴다. 이 과제는 gRPC가 필수라 gRPC-Web을 택했다.

## 4. 핵심 설계: 수강신청 폭주 대응

### 4.1 폭주가 어디서 터지는가
| 단계 | 문제의 성격 | 증상 |
|---|---|---|
| 신청 페이지 진입 (정각) | **요청량** 폭주. 모두가 같은 순간에 같은 무거운 일(인증, 학생 정보, 미리담기, 시간표 조회)을 한다 | DB 커넥션 풀 고갈 → 서버 스레드 고갈 → 타임아웃 → 재시도 → 연쇄 장애 |
| 과목별 신청 | **같은 데이터**에 대한 동시 쓰기(경합). 인기 과목의 정원 하나에 수백 명이 몰린다 | 행 잠금 대기, 커넥션 점유로 전체 지연 |

8/11 당일 관찰: 대부분의 학생은 새로고침하지 않고 기다렸지만 접속되지 않았다. 새로고침한 학생은 바로 접속됐다. 기존 요청이 "느린" 것이 아니라 "죽어 있었다"는 신호다 (멈춘 서버로 계속 라우팅, 타임아웃 부재, 대기열 자리 미반납 등으로 추정. 실제 원인은 비공개).

### 4.2 대응 전략 요약
| 문제 | 대응 |
|---|---|
| 진입 폭주 | **대기열**이 입장 인원을 서버 처리량 이하로 제한 |
| 로그인 폭주 | **사전 로그인** (오픈 10분 전부터 로그인, JWT 유효기간 넉넉히) |
| 재시도 폭주, 매크로 | 서버가 조회 간격 지정 + **학번별 요청 제한** + 단계적 불이익 |
| 죽은 요청 | 모든 호출에 **gRPC deadline**, 클라이언트 자동 재시도(exponential backoff + jitter), Envoy 헬스체크 |
| 콜드 스타트 | 오픈 전 예열: 과목 캐시와 정원 숫자를 Redis에 미리 적재 |
| 정원 경합 | **Redis 원자적 차감** (Lua 스크립트로 확인과 차감을 한 번에) |
| DB 쓰기 폭주 | **Kafka**로 신청 이벤트를 쌓고 consumer가 일정한 속도로 저장 |

### 4.3 대기열 (Waiting Room)
- 키: **학번** (로그인 후 줄을 선다). `ZADD queue NX <접속 시각> <학번>`. 이미 줄에 있으면 덮어쓰지 않으므로 클라이언트를 재시작해도 순번이 유지되고, 한 학생이 여러 번 줄을 설 수 없다.
- 순번 조회: `ZRANK`. 응답에 "다음 조회까지 간격"을 담아 클라이언트가 그 간격으로만 조회하게 한다.
- 입장 방식: **빈자리만큼 입장**. queue-worker 하나가 주기적으로 `빈자리 = 동시 입장 한도 - 살아 있는 입장권 수`를 계산해 `ZPOPMIN`으로 꺼내고 입장권(`pass:{학번}`, TTL)을 발급한다.
  - 입장권 TTL로 나간 사람의 자리가 반드시 반납된다 (대기열 멈춤 방지).
  - 동시 입장 한도 = 서버 1대 수용 인원 × 서버 수. 수용 인원은 부하 테스트로 정한다.
  - 입장 결정 주체를 하나(queue-worker)로 둔다. 서버 여러 대가 각자 세면 한도가 대수만큼 늘어난다.
- 현업 근거: Cloudflare Waiting Room은 "동시 활성 사용자 수 + 분당 신규 입장 수"를 운영자가 설정한다. 응답 속도를 보고 자동 조절하는 방식(Netflix concurrency-limits, Envoy adaptive concurrency)은 주로 서버 자기 보호 계층에서 쓴다.
- 익명 번호표 방식과의 비교: 로그인 전 대기열(외부 솔루션)은 사용자를 몰라 익명 번호표를 쓰고, 번호표를 잃으면(새로고침) 맨 뒤로 밀린다. 우리는 학번 기반이라 순번이 유지되는 대신, 로그인 부하를 사전 로그인으로 분산해야 한다.

### 4.4 수강신청 처리 흐름
```
클라이언트 ──Enroll(과목, JWT)──▶ Envoy ──▶ 서버
서버: 입장권 확인 (Redis)
서버: 선수과목, 시간표 충돌, 학점 한도 검사          ← DB 트랜잭션 밖에서 미리
서버 → Redis (Lua 스크립트 한 번):
        이미 신청? → 정원 남음? → 정원 1 감소 + 신청 표시
        실패 → "마감" / "이미 신청함"
서버 → Kafka: "학번이 과목 신청함" 이벤트 (acks=all 확인 후)
        실패 → Redis 정원 되돌리고 실패 응답
서버 → 클라이언트: "신청 완료"

enrollment-consumer: Kafka에서 꺼내 DB에 저장 (INSERT ... ON CONFLICT DO NOTHING)
        저장 성공 후에만 offset 확정 (at-least-once + 멱등 저장)
```

### 4.5 실패 처리
| 상황 | 대응 |
|---|---|
| Kafka 기록 실패 | 사용자에게 성공을 알리기 전이므로 Redis 정원을 되돌리고 실패 응답 |
| consumer 또는 DB 실패 | 메시지가 Kafka에 남아 있어 재시작 후 재시도. 중복은 유니크 제약으로 무시 |
| 재시도해도 실패 | Dead Letter Topic으로 보내고, 정원 복구, 사용자 통지, 운영자 경보 |
| 서버가 Redis 차감 후 Kafka 기록 전에 다운 | 주기적 정합성 점검(reconciliation)으로 Redis와 DB를 대조해 복구 |
| Redis 재시작 | AOF로 복구. 정원 숫자는 DB 기준(정원 - 신청 수)으로 재계산 가능 |
| 서버 한 대 멈춤 | Envoy 헬스체크가 제외. 서버는 stateless(JWT)라 세션 고정이 없다 |

## 5. 데이터 설계

### 5.1 PostgreSQL (초안)
```
student          (student_id PK, name, department, password_hash, salt, role)
course           (course_id PK, name, instructor_name, capacity)
course_prereq    (course_id, prereq_course_id)
completed_course (student_id, course_id)
enrollment       (student_id, course_id, created_at, UNIQUE(student_id, course_id))
```
기존 txt 데이터(학생 + 이수 과목, 과목 + 선수과목)를 그대로 옮기고, 정원, 비밀번호, 권한을 추가한다. 기본 과제 단계에서는 인증과 정원 컬럼 없이 시작하고, 기능을 추가하면서 확장한다.

### 5.2 Redis 키
| 키 | 자료구조 | 용도 | TTL |
|---|---|---|---|
| `queue` | Sorted Set (학번 → 접속 시각) | 대기열 | 행사 종료까지 |
| `pass:{학번}` | String | 입장권 | 약 3분 |
| `seat:{과목}` | String (정수) | 남은 정원 | 행사 종료까지 |
| `enrolled:{과목}` | Set | 중복 신청 방지 | 행사 종료까지 |
| `cache:courses` | String | 과목 목록 캐시 | 약 60초 |
| `rate:{학번}` | String (정수) | 1초당 요청 수 | 1초 |
| `violation:{학번}` | String (정수) | 위반 횟수 | 수 분 |

### 5.3 Kafka
| 토픽 | 내용 | 소비자 |
|---|---|---|
| `enrollment-events` | 신청/취소 확정 이벤트 | enrollment-consumer (DB 저장), 필요 시 감사 로그, 알림 |
| `enrollment-events-dlt` | 재시도 불가 실패 | 운영자 확인 |

## 6. 공통 기능 (gRPC ServerInterceptor)
모든 rpc가 같은 순서로 통과한다.
```
요청 → ① 로깅: trace ID 발급, 시작 시각 기록 (SLF4J + Logback, JSON)
     → ② 인증: JWT 검증, 권한 확인 (학생 / 관리자)
     → ③ 요청 제한: Redis 카운터 (INCR + EXPIRE), 위반 시 경고 → 일시 차단 → 대기열 맨 뒤
     → ④ 서버 보호: 동시 처리 중 요청이 한도를 넘으면 즉시 RESOURCE_EXHAUSTED
     → rpc 처리
     → ① 로깅: 소요 시간, 결과 코드, 지표(Prometheus) 기록
```
- 비밀번호: PBKDF2(`PBKDF2WithHmacSHA256`, Java 기본 제공)로 해시 저장.
- nginx 대신 Envoy에서도 IP별 제한을 느슨하게 둔다. 학교는 여러 학생이 같은 공인 IP를 공유하므로 IP 기준 제한은 보조다.

## 7. gRPC 서비스 (클라이언트 ↔ 서버)
| 분류 | rpc | 권한 |
|---|---|---|
| 인증 | Login | 누구나 |
| 조회 | GetStudentList, GetCourseList | 로그인 |
| 학생 관리 | AddStudent, DeleteStudent | 관리자 |
| 과목 관리 | AddCourse, DeleteCourse | 관리자 |
| 대기열 | EnterQueue, GetQueueStatus | 학생 |
| 수강신청 | Enroll, CancelEnrollment, GetMyEnrollments | 학생 (입장권 필요) |

## 8. 배포
- **VM**: UTM(QEMU, aarch64) 위에 Ubuntu 24.04 Server 클라우드 이미지로 3대를 만든다. cloud-init으로 계정, SSH 키, 고정 IP, Docker 설치를 자동화한다. 노트북에서 `ssh sugang-server`처럼 이름으로 접속한다.

| VM | IP | 시작 사양 | 컨테이너 |
|---|---|---|---|
| sugang-server | 192.168.64.20 | 2 vCPU, 2GB | nginx, Envoy, server ×2 (+ queue-worker, consumer) |
| sugang-db | 192.168.64.21 | 1 vCPU, 1GB | PostgreSQL, postgres-exporter |
| sugang-infra | 192.168.64.22 | 2 vCPU, 3GB | Redis, Kafka, Prometheus, Grafana |

- **Docker Compose**: VM마다 역할별 Compose 파일(`deploy/<역할>/docker-compose.yml`)로 띄운다. 교수님 컴퓨터용으로 한 대에서 전부 띄우는 통합 Compose(`docker compose up --build`, 브라우저 http://localhost)도 둔다.
- **프론트 파일**: 소스는 레포의 `web/`에 있고, Docker 빌드에서 `npm run build`한 결과물(HTML, JS)을 nginx 이미지에 넣어 서버 VM에서 제공한다. 운영 환경이라면 CDN에 올린다.
- **확장**: `docker compose up --scale` 또는 서버 컨테이너 추가로 수동 확장한다. Compose에는 오토스케일 기능이 없다.
- 운영 환경이라면: Kubernetes HPA 오토스케일 + 오픈 시각 전 사전 확장(pre-scaling). 오토스케일은 수 분이 걸려 1~2초짜리 정각 폭주를 따라가지 못하므로, 시각이 정해진 이벤트는 사전 확장이 주력이다.

## 9. 용량 산정과 검증 계획

### 9.1 8/11 트래픽 추정 (측정 전 추정치)
| 구간 | 필요한 양 | 비고 |
|---|---|---|
| 사전 로그인 (10분 분산) | 초당 약 6건 | 정각에 몰리면 PBKDF2 때문에 45~90초 밀림 |
| 대기열 진입 | 1~3초에 3,660건 | Redis 처리량의 극히 일부 |
| 순번 조회 (2초 간격) | 초당 최대 약 1,830건 | |
| 수강신청 | 1인당 약 6건, 총 약 22,000건 | DB 처리량의 70%를 목표로 입장 속도 결정 |

추정치는 구현 후 `redis-benchmark`, `pgbench`, k6 부하 테스트로 측정한 값으로 교체한다.

### 9.2 실험
| 실험 | 비교 | 지표 |
|---|---|---|
| 대기열 효과 | A. 대기열 없음 + 재시도 vs B. 대기열 + 입장 제한 | 처리량, 응답 시간 p50/p95/p99, 오류율, 마지막 학생 대기 시간 |
| Kafka 효과 | B. DB 동기 저장 vs C. Kafka 비동기 저장 (DB 저사양 제한) | 신청 응답 시간, consumer lag, drain time |
| 정원 정확성 | 인기 과목 쏠림 | 정원 초과 0건, 중복 신청 0건 |
| 최소 사양 | VM 사양을 작게 시작해 통과 기준까지 증가 | 통과한 최소 vCPU/RAM |
| 프록시 경로 | A. 브라우저 → nginx → Envoy → 서버 (단일 출처) vs B. 데이터는 브라우저 → Envoy → 서버 직접 (CORS) | 데이터 요청 p50/p95/p99, 처리량, 프록시 CPU |

- 테스트 데이터: 가상 학생 3,660명, 정원이 다양한 가상 과목 수백 개, 인기 과목 쏠림.
- Kafka 실험 전제: "DB를 쉽게 키울 수 없는 상황(학사 시스템과 공유하는 레거시 DB)". 그렇지 않으면 DB 사양을 올리는 것이 더 싸다.

### 9.3 한계
- 노트북 내부 VM 간 통신이라 실제 네트워크 지연이 없다.
- 부하 생성기(노트북)와 VM들이 같은 노트북의 CPU와 메모리를 나눠 쓴다.
- 재현하는 것은 트래픽 패턴이며, 명지대의 실제 시스템 구성은 알 수 없다.
- Kafka와 Redis를 각 1대로 운영하므로 복제(Replica, Sentinel, Kafka replication)에 의한 내구성 보장은 없다.

## 10. 단계별 구현 계획
1. **기본 과제**: PostgreSQL 전환 (Data 계층 제거, 서버가 JDBC로 직접 접근) + 학생/과목 추가·삭제. 콘솔 클라이언트 유지.
2. **Docker Compose**: PostgreSQL, 서버 컨테이너화.
3. **인증**: Login rpc, PBKDF2, JWT, 인증 Interceptor.
4. **로깅과 모니터링**: Logback JSON 로그, trace ID, Prometheus 지표, Grafana.
5. **수강신청**: 스키마 확장(정원, 신청), 선수과목 검사, Redis 원자적 차감.
6. **대기열**: EnterQueue/GetQueueStatus, queue-worker, 입장권 TTL, 요청 제한.
7. **확장 구성**: Envoy 로드밸런싱(서버 2대), 헬스체크, deadline과 재시도.
8. **Kafka**: enrollment-events, consumer, 실패 처리, 정합성 점검.
9. **웹 클라이언트**: React + TypeScript, gRPC-Web.
10. **부하 테스트와 측정**: k6 시나리오, VM 3대 배포, 실험 A/B/C, 최소 사양 찾기, Grafana 그래프 수집.
