# 수강신청 시스템 아키텍처

> 대용량 트래픽(수강신청 오픈 순간의 폭주)을 가정한 3계층 gRPC 수강신청 시스템의 설계 문서.
> 결정 과정과 근거의 시간순 기록은 [REPORT_LOG.md](REPORT_LOG.md), 3계층 + MSA 전환 계획과 계약은 [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), 배포 방법은 [deploy/README.md](deploy/README.md)에 있다.

## 1. 목표와 범위

### 기능 요구사항
| 기능 | 설명 |
|---|---|
| 학생 관리 | 학생 목록 조회, 추가, 삭제 (교수) |
| 과목 관리 | 과목 목록 조회, 추가, 삭제 (교수) |
| 조회 | 학생별 수강과목, 과목별 수강학생, 학생별 이수과목 |
| 수강신청 | 대기열, 입장권, 정원, 중복, 선수과목, 시간 충돌, 같은 과목, 학점 한도 검사, 취소 |
| 인증 | 로그인(JWT), 역할(학생, 교수, 서비스)별 권한 |
| 로깅 | 모든 요청의 접근 로그(사용자, 명령, 결과, 시간)와 서버 로그 |

### 품질 요구사항
- 폭주 대응: 명지대학교 2026년 8/11 수강신청 대상 3,660명(인문대학 1,748명, 사회과학대학 1,347명, 인공지능소프트웨어융합대학 565명)이 정각 1~3초 안에 접속하는 상황을 견딘다.
- 정확성: 정원 초과 0건, 같은 강의 중복 신청 0건, 한 학생이 동시에 보낸 신청도 규칙을 어기지 않는다.
- 장애 격리: 서비스마다 복제본 두 개를 두고, 하나가 멈춰도 Envoy가 다른 쪽으로 보낸다.
- 재현성: `docker compose up` 한 번으로 전체 시스템을 실행한다.

### 구현 원칙
- 서버 쪽은 Java로 최대한 직접 구현한다. gRPC 서비스, 범용 Data 서버(저장소), 로깅 백엔드, 커넥션 풀, Redis 클라이언트, JWT, PBKDF2 해시를 직접 만들었다.
- 통신(gRPC, protobuf)과 Kafka 클라이언트처럼 프로토콜이 복잡한 부분, 그리고 로드밸런서(Envoy), Redis, Kafka, 모니터링 같은 인프라는 기성 도구를 쓴다.
- 도메인 판단은 모두 Server 계층(Java)에 둔다. Data 서버는 컬렉션 이름과 필드 이름을 문자열로만 받고 도메인을 모른다.

## 2. 전체 구성

```
Client (Java 콘솔)
   | gRPC (HTTP/2)
Envoy :8080  서비스 경로 접두사로 라우팅, 복제본 두 개에 라운드로빈
   ├─ /serverarchitecture.AuthService/        → auth1, auth2             :9101
   ├─ /serverarchitecture.AcademicService/    → academic1, academic2     :9102
   └─ /serverarchitecture.EnrollmentService/  → enrollment1, enrollment2 :9103
                                                   | gRPC (서비스 토큰, Envoy 경유)
                                                   → academic
auth, academic, enrollment, queue-worker, enrollment-consumer
   | gRPC
Data 서버 :9200  (도메인 용어 없는 범용 CRUD, 메모리 + 컬렉션별 append-only 로그 파일)

Redis :6379  대기열, 입장권, 좌석, 학생 잠금, enroll.lua
Kafka :9092  enrollment-events, enrollment-events-dlt
Prometheus :9090, Grafana :3000
```

### 구성 요소
| 구성 요소 | 역할 | 기술 | 직접 구현 |
|---|---|---|---|
| 콘솔 클라이언트 | 로그인, 역할별 메뉴, 대기열 스트리밍 표시, 수강신청 | Java + gRPC | O |
| Envoy | 서비스별 라우팅, 로드밸런싱, 재시도, 이상 엔드포인트 제외 | Envoy | 설정만 |
| auth x2 | 로그인, JWT 발급 | Java + gRPC | O |
| academic x2 | 학생, 과목, 개설 강의, 이수 기록, 수강 현황 조회와 관리 | Java + gRPC | O |
| enrollment x2 | 대기열, 입장권, WatchQueue 스트리밍, 수강신청과 취소 | Java + gRPC | O |
| queue-worker | 빈자리만큼 대기열에서 입장 처리, 좌석 프리웜 | Java | O |
| enrollment-consumer | Kafka 신청 이벤트를 Data 서버에 멱등 저장 | Java + kafka-clients | O |
| Data 서버 | 범용 컬렉션 저장소(생성, 조회, 조건 조회, 비교 후 수정, upsert, 삭제, 개수) | Java + gRPC | O |
| Redis | 대기열, 입장권, 좌석, 학생 잠금 | Redis | 설정만 |
| Kafka | 수강신청 확정 이벤트 로그 | Kafka | 설정만 |
| Prometheus + Grafana | 지표 수집과 시각화 | Prometheus, Grafana, redis_exporter | 설정만 |

### Gradle 모듈
| 모듈 | 내용 |
|---|---|
| Proto | 모든 .proto와 생성 코드 |
| Common | 자체 로거(`com.serverarchitecture.logging`, SLF4J 프로바이더) |
| Data | Data 서버 `com.serverarchitecture.data` |
| Server | auth, academic, enrollment 서비스, QueueWorker, EnrollmentConsumer, 자체 Redis 클라이언트와 풀 |
| Client | 콘솔 `com.serverarchitecture.client` |

Server 모듈 최상위 패키지에는 세 서비스가 함께 쓰는 부품이 있다: `AuthInterceptor`, `AccessLogInterceptor`, `JwtProvider`, `PasswordHasher`, `GrpcResponder`(예외를 gRPC 상태로 바꿔 응답), `UserRepository`, `DataClient`, `SeedLoader`(시작 시드와 재시도), `GrpcServerRunner`. 서비스 패키지(auth, academic, enrollment)끼리는 서로 의존하지 않는다.

## 3. 계층 구조와 통신 방식

### 3계층: 클라이언트 → 서버 → 데이터
- 클라이언트 계층: 콘솔. Envoy 주소 하나만 안다.
- 서버 계층: auth, academic, enrollment와 두 백그라운드 프로세스. 모든 규칙과 판단이 여기에 있다.
- 데이터 계층: Data 서버. PostgreSQL을 빼고 직접 만든 범용 저장소로 바꿨다. 여러 서버가 같은 파일을 동시에 쓰면 깨지므로, 파일을 혼자 책임지는 Data 서버를 두고 서버들은 gRPC로만 접근한다.

### 통신 방식
| 구간 | 프로토콜 | 비고 |
|---|---|---|
| 콘솔 → Envoy | gRPC | 로그인 JWT를 Bearer로 붙인다 |
| Envoy → 서비스 | gRPC | 경로 접두사로 서비스 선택. WatchQueue는 timeout 0s, idle 300s |
| enrollment → academic | gRPC, Envoy 경유 | `Role.SERVICE` 서비스 토큰. 학사 복제본 두 개에 분산 |
| 서비스, 워커, 컨슈머 → Data | gRPC | Envoy에 노출하지 않는 내부망 |
| enrollment, worker → Redis | RESP (직접 구현한 클라이언트와 풀) | |
| enrollment → Kafka → consumer | Kafka 프로토콜 | acks=all |

### 실패 응답
- 실패는 응답 필드가 아니라 gRPC 상태로 보낸다. 설명(description)은 콘솔에 그대로 찍히는 한국어 문장이다.
- 반드시 있어야 할 데이터가 없으면 `NullDataException`(NOT_FOUND)을 던진다.

| 상황 | 상태 코드 |
|---|---|
| 로그인 실패 | UNAUTHENTICATED |
| 권한 없음, 학생이 다른 학생 조회 | PERMISSION_DENIED |
| 중복 신청, 같은 과목 다른 분반, 학생이나 과목 중복 추가 | ALREADY_EXISTS |
| 선수과목 미이수, 시간 충돌, 같은 학생의 앞선 신청 처리 중 | ABORTED |
| 정원 마감 | RESOURCE_EXHAUSTED |
| 학점 한도 초과 | OUT_OF_RANGE |
| 입장권 없음 또는 만료, 오픈 전 진입, 신청 내역이 있는 학생 삭제, 개설 강의가 있는 과목 삭제 | FAILED_PRECONDITION |
| 없는 학생, 과목, 강의, 신청 내역 | NOT_FOUND |
| Data, 학사 서비스, Redis 연결 실패 | UNAVAILABLE |

수강신청 쪽에서 `FAILED_PRECONDITION`은 입장권 문제에만 쓴다. 콘솔은 이 코드를 받으면 대기열 상태를 다시 확인하고, 입장권이 없으면 메뉴로 돌아가 다시 줄을 세운다. 규칙 위반에 같은 코드를 쓰면 학생이 줄 밖으로 밀려나므로 `ABORTED`를 쓴다.

## 4. 핵심 설계: 수강신청 폭주 대응

### 4.1 폭주가 어디서 터지는가
| 단계 | 문제의 성격 | 대응 |
|---|---|---|
| 신청 페이지 진입 (정각) | 요청량 폭주 | 대기열이 입장 인원을 서버 처리량 이하로 제한 |
| 과목별 신청 | 같은 정원에 동시 쓰기 | Redis Lua 한 번으로 확인과 차감 |
| 저장 | 순간 쓰기 폭주 | Kafka에 쌓고 consumer가 일정한 속도로 Data 서버에 저장 |

### 4.2 대기열과 WatchQueue
- 키는 학번이다. `ZADD queue:<학기> NX <접속 시각> <학번>`이라 다시 들어와도 순번이 유지되고 한 학생이 여러 번 줄을 설 수 없다.
- queue-worker 하나가 0.5초마다 `admit.lua`로 `빈자리 = 한도(max:<학기>, 없으면 QUEUE_MAX_ACTIVE 50) - 살아 있는 입장권 수`만큼 `ZPOPMIN`하고 입장권(`active:<학기>` ZSET, 점수 = 만료 시각)을 준다. 첫 입장권은 30초 안에 써야 하고, 쓰면 300초로 늘어난다. 30초 넘게 하트비트가 없는 대기자는 줄에서 뺀다.
- `WatchQueue`는 서버 스트리밍이다. 첫 상태를 바로 보내고, 1초마다 순번을 확인해 바뀔 때만 보낸다. 스트림이 열려 있는 동안 매 확인이 하트비트(`seen:<학기>` 갱신)다. ADMITTED 또는 NOT_IN_QUEUE를 보내면 스트림을 닫는다. 스트림마다 스레드를 만들지 않고 공유 ScheduledExecutor 하나로 돌린다.
- 콘솔은 입장권이 없으면 `EnterQueue` 후 `WatchQueue`를 받으며 "앞에 N명 대기 중..."을 출력하고, ADMITTED가 오면 개설 강의 표를 띄운다.

### 4.3 수강신청 처리 흐름
```
콘솔 ──Enroll(강의, JWT)──▶ Envoy ──▶ enrollment
enrollment: 입장권 확인과 연장 (Redis)
enrollment → academic (Envoy 경유, 서비스 토큰): 학생 정보(이수 과목, 최대 학점)
enrollment: 개설 강의 존재(메모리 캐시 OfferingCatalog), 선수과목 검사
enrollment: 학생 잠금 획득 (Redis SET NX PX, 무작위 토큰, 10초)
    중복, 시간 충돌, 같은 과목, 학점 한도 검사 (Redis의 내 신청 목록 기준)
    enroll.lua 한 번: 이미 신청? → 정원 남음? → 좌석 1 감소 + 신청 표시
    Kafka에 ENROLLED 이벤트 (acks=all), 실패하면 Redis를 되돌리고 UNAVAILABLE
학생 잠금 해제 (unlock.lua, 내 토큰일 때만 DEL)
enrollment → 콘솔: "신청이 완료되었습니다."

enrollment-consumer: Kafka에서 꺼내 Data 서버에 Upsert (id = 학번:개설강의id, 멱등)
    저장 성공 후에만 offset 커밋
```
- 학생 잠금은 같은 학생이 시간이 겹치는 두 강의를 동시에 신청할 때 둘 다 검사를 통과하는 경쟁을 막는다. 정원은 강의 단위 Lua가, 학생 단위 규칙은 학생 잠금이 지킨다.
- 개설 강의 목록은 enrollment가 학사 서비스의 `GetCatalog`로 읽어 메모리에 두고 60초마다 갱신한다. 시작할 때 학사 서비스가 준비되지 않았으면 0.5초부터 두 배씩(최대 10초) 늘려 가며 다시 시도하고, 성공한 뒤부터 주기 갱신으로 넘어간다.

### 4.4 실패 처리
| 상황 | 대응 |
|---|---|
| Kafka 기록 실패 | 성공을 알리기 전이므로 Redis 좌석을 되돌리고 UNAVAILABLE |
| Data 서버 일시 장애(UNAVAILABLE, DEADLINE_EXCEEDED 등) | consumer가 offset을 커밋하지 않고 0.5초부터 8초까지 백오프하며 다시 시도. 같은 키로 Upsert하므로 중복은 무해 |
| 재시도해도 안 되는 메시지(형식 오류 등) | `enrollment-events-dlt`로 보내고 넘어간다 |
| Data 서버 재시작 | 컬렉션별 로그 파일을 재생해 같은 내용, 같은 생성 순서로 복구 |
| 서비스 복제본 하나 멈춤 | Envoy 재시도(connect-failure, refused-stream)와 outlier detection으로 다른 복제본에 보냄 |
| 학사 서비스가 enrollment보다 늦게 뜸 | OfferingCatalog가 백오프 재시도, 요청 시에도 비어 있으면 다시 읽음 |
| 시작 순서 | auth, academic은 시드 적재를 최대 30번(1초 간격) 재시도. compose 헬스체크와 `restart: unless-stopped`가 순서를 받친다 |
| 서버가 Redis 차감 후 Kafka 기록 전에 다운 | 좌석만 줄어든다. Redis와 Data를 맞추는 대조 작업은 없다(한계) |

## 5. 데이터 설계

### 5.1 Data 서버
- API(`data.proto`): `Create`, `Read`, `ReadAll`, `ReadWhere(field, value)`, `Update(expected)`, `Upsert(expected)`, `Delete`, `Count`. 항목은 `Item { id, map<string,string> fields }`.
- 컬렉션은 첫 쓰기 때 생기고 이름은 `[a-z0-9_]+`만 허용한다. 한 컬렉션 안의 연산은 컬렉션별 잠금으로 선형화된다.
- 저장: 컬렉션별 메모리 맵(생성 순서 유지) + `DATA_DIR/<컬렉션>.log`. 한 줄이 `<P|D> <CRC32 8자리> <base64(Item protobuf 또는 id)>`라 탭과 줄바꿈이 들어간 값도 안전하다. 시작 시 재생하고, 손상되거나 줄바꿈 없이 끝난 마지막 기록은 경고를 남기고 잘라 낸다. 재생한 기록 수가 항목 수보다 많으면(덮어쓰기, 삭제) 로그를 압축한다.
- 쓰기는 파일 기록이 끝난 뒤 응답한다. fsync는 `DATA_FSYNC`(always, batch, never, 기본 always).

### 5.2 컬렉션
| 컬렉션 | id | 필드 | 쓰는 쪽 |
|---|---|---|---|
| `users` | 사용자 id | `name`, `role`, `password_hash` | auth(읽기), academic(학생 추가와 삭제) |
| `students` | 학번 | `name`, `department`, `max_credits`, `completed_course_ids` | academic |
| `courses` | 과목 id | `name`, `instructor_name`, `credits`, `prerequisite_course_ids` | academic |
| `offerings` | 개설 강의 id | `course_id`, `semester`, `section`, `professor_id`, `capacity`, `room`, `times` | academic(읽기), queue-worker(읽기) |
| `enrollments` | `학번:개설강의id` | `student_id`, `offering_id`, `semester`, `status`, `applied_at_ms` | consumer(쓰기), academic, queue-worker(읽기) |

- 비밀번호는 `pbkdf2$100000$<salt>$<key>`(PBKDF2WithHmacSHA256). 시드 비밀번호는 모두 아이디와 같다.
- 시드는 `Server/src/main/resources/seed/*.tsv`(학생 18명, 교수 5명, 과목 7개, 2026-2 개설 강의 8개). 컬렉션이 비었을 때만 넣는다.
- `enrollments`는 Kafka를 거쳐 비동기로 채워진다. 실시간 기준은 Redis이고, 학사 서비스의 "학생별 수강과목", "과목별 수강학생"은 몇 초 늦을 수 있다.

### 5.3 Redis 키 (학기별)
| 키 | 자료구조 | 용도 |
|---|---|---|
| `queue:<학기>` | ZSET (학번 → 접속 시각) | 대기열 |
| `seen:<학기>` | ZSET (학번 → 마지막 확인 시각) | 대기자 하트비트 |
| `active:<학기>` | ZSET (학번 → 입장권 만료 시각) | 입장권 |
| `max:<학기>` | String | 동시 입장 한도(없으면 `QUEUE_MAX_ACTIVE`) |
| `seats:<학기>` | Hash (강의 → 남은 자리) | 좌석 |
| `ready:<학기>` | String | 프리웜 완료 표시 |
| `open:<학기>` | String | 오픈 시각(있으면 그 전 진입 거부) |
| `student:offerings:<학기>:<학번>` | Set | 학생의 신청 강의 |
| `lock:student:<학기>:<학번>` | String (PX) | 학생 단위 신청 잠금 |

### 5.4 Kafka
| 토픽 | 내용 | 소비자 |
|---|---|---|
| `enrollment-events` (파티션 3) | 신청과 취소 확정 이벤트 | enrollment-consumer |
| `enrollment-events-dlt` | 재시도 불가 실패 | 운영자 확인 |

## 6. 공통 기능 (gRPC ServerInterceptor)
```
요청 → AccessLogInterceptor: 사용자, 명령, 결과 코드, 소요 시간을 access.log에 한 줄
     → AuthInterceptor: JWT 검증, PERMISSIONS 표로 역할 확인 (Login은 제외)
     → rpc 처리 → GrpcResponder가 CustomException과 Data 오류를 gRPC 상태로 변환
```
- 권한표는 각 `*Server.java`의 `PERMISSIONS`에 있다. 학생이 학사 서비스의 `ListCoursesByStudent`, `ListCompletedCourses`를 부르면 자기 학번만 허용한다.
- 로그는 직접 만든 SLF4J 백엔드로 `access.log`와 `server.log`에 나눠 쓰고, 자정마다 날짜를 붙여 넘긴다.

## 7. gRPC 서비스
| 서비스 | rpc | 권한 |
|---|---|---|
| AuthService | Login | 누구나 |
| AcademicService | ListStudents, AddStudent, DeleteStudent, AddCourse, DeleteCourse, ListStudentsByCourse | 교수 |
| | ListCourses | 교수, 학생 |
| | ListCoursesByStudent | 교수, 학생(본인) |
| | ListCompletedCourses | 교수, 학생(본인), 서비스 |
| | GetCatalog | 교수, 학생, 서비스 |
| | GetStudent | 교수, 서비스 |
| EnrollmentService | EnterQueue, GetQueueStatus, WatchQueue(스트리밍), LeaveQueue | 학생 |
| | ListOfferings, Enroll, CancelEnrollment, ListMyEnrollments | 학생 (입장권 필요) |
| | GetSchedule | 학생, 교수 |

## 8. 배포
| VM | IP | 컨테이너 |
|---|---|---|
| sa-lb | 192.168.64.30 | envoy |
| sa-app1 | 192.168.64.31 | auth, academic, enrollment |
| sa-app2 | 192.168.64.32 | auth, academic, enrollment |
| sa-db | 192.168.64.33 | data |
| sa-infra | 192.168.64.34 | redis, kafka, queue-worker, enrollment-consumer, prometheus, grafana |

- 이미지는 `sa-server` 하나에서 entrypoint(`bin/AuthServer`, `AcademicServer`, `EnrollmentServer`, `QueueWorker`, `EnrollmentConsumer`)로 구분하고, Data 서버는 `sa-data`, 볼륨 `/app/data`.
- 로컬 올인원(`docker-compose.yml` → `deploy/allinone/docker-compose.yml`)은 한 호스트에서 위 구성을 모두 띄운다. 시작 순서는 data → auth, academic → envoy, redis, kafka → enrollment → queue-worker, consumer이고, 각 서비스는 TCP 헬스체크와 `restart: unless-stopped`를 가진다.
- VM 구성 파일(`deploy/app`, `deploy/db`, `deploy/infra`, `deploy/lb`)은 새 구성에 맞췄지만 아직 VM에 배포하지 않았다.

## 9. 검증
- 단위와 통합 테스트: `./gradlew build` 178개(Data 31, Server 122, Client 14, Common 11). Server의 Redis 통합 테스트 24개는 `REDIS_IT_PORT`가 있을 때만 돈다.
- 로컬 Docker e2e: `deploy/allinone/e2e-local.sh` 26개 검사(로그인, 조회, 대기열과 WatchQueue, 신청 규칙과 상태 코드, 취소, 17명 동시 신청, 같은 학생의 충돌 신청, Data 서버 정합성, Data 재시작, 복제본 장애). 결과는 [docs/e2e/e2e-local.txt](docs/e2e/e2e-local.txt).
- 콘솔 시나리오: [docs/e2e/console-student.txt](docs/e2e/console-student.txt), [docs/e2e/console-professor.txt](docs/e2e/console-professor.txt).

## 10. 한계
- Redis, Kafka, Data 서버가 각각 한 대라 단일 장애 지점이다. Data 서버는 복제가 없다.
- 서버가 Redis 좌석을 차감한 직후 Kafka 기록 전에 죽으면 좌석만 줄어든다. 대조 작업은 없다.
- 개설 강의 캐시는 enrollment 복제본마다 60초 주기라, 개설 강의 변경이 최대 60초 늦게 보인다.
- 예전 구조(PostgreSQL, REST 트랜스코딩, React 웹)에서 잰 부하 테스트 수치는 새 구조에서 다시 재지 않았다. `web/`, `demo/`는 예전 REST API 기준이라 지금은 동작하지 않는다.
