# 3계층 + MSA 전환 구현 계획

브랜치 `feature/three-tier-msa`. 상태: 완료(2026-10-23). 1단계(뼈대와 계약), 2단계(A, B1, B2, C, D 병렬 구현), 3단계(통합, 후속 정리, 로컬 Docker e2e)가 모두 끝났다. 결과와 남은 일은 10장에 있다.

## 1. 목표 구조

```
Client (Java 콘솔)
   | gRPC (HTTP/2)
Envoy :8080  ── 서비스 경로 접두사로 라우팅
   ├─ /serverarchitecture.AuthService/        → auth x2       :9101
   ├─ /serverarchitecture.AcademicService/    → academic x2   :9102
   └─ /serverarchitecture.EnrollmentService/  → enrollment x2 :9103
                                                  | gRPC (서비스 토큰)
                                                  → academic (Envoy 경유)
auth, academic, enrollment, QueueWorker, EnrollmentConsumer
   | gRPC
Data 서버 :9200 (도메인 용어 없는 범용 CRUD, 메모리 + 파일)

Redis: 대기열, 입장권, 좌석, enroll.lua (그대로)
Kafka: enrollment-events, enrollment-events-dlt (그대로)
```

- PostgreSQL, JDBC, Flyway, pgjdbc, bcrypt 라이브러리는 제거했다.
- 모든 도메인 판단은 Server 계층(Java)에 있다. Data 서버는 컬렉션 이름과 필드 이름을 문자열로만 받는다.
- React 웹(web/)과 gRPC-Web/JSON 트랜스코딩은 범위에서 뺐다. web/, demo/의 REST 기반 스크립트는 손대지 않으며 동작하지 않을 수 있다.

## 2. Gradle 모듈

| 모듈 | 내용 | 의존 |
|---|---|---|
| Proto | 모든 .proto와 생성 코드 | grpc, protobuf |
| Common | 자체 로거(`com.serverarchitecture.logging`, SLF4J 프로바이더) | slf4j-api(api) |
| Data | Data 서버 `com.serverarchitecture.data` | Proto, Common, grpc |
| Server | auth, academic, enrollment 서비스, QueueWorker, EnrollmentConsumer, 자체 Redis 클라이언트와 풀 | Proto, Common, grpc, kafka-clients |
| Client | 콘솔 `com.serverarchitecture.client` | Proto, grpc |

의존성 목록은 확정이다. 새 라이브러리 추가 금지(테스트용 `io.grpc:grpc-inprocess`는 Data, Server에 이미 있음).

실행 진입점(main):

| 프로세스 | main 클래스 | 시작 스크립트(installDist) |
|---|---|---|
| Data 서버 | `com.serverarchitecture.data.DataServer` | `Data/build/install/Data/bin/Data` |
| 로그인 서비스 | `com.serverarchitecture.auth.AuthServer` | `Server/.../bin/AuthServer` |
| 학사 서비스 | `com.serverarchitecture.academic.AcademicServer` | `Server/.../bin/AcademicServer` |
| 수강신청 서비스 | `com.serverarchitecture.enrollment.EnrollmentServer` | `Server/.../bin/EnrollmentServer` (기본 `bin/Server`도 같음) |
| 대기열 워커 | `com.serverarchitecture.enrollment.QueueWorker` | `Server/.../bin/QueueWorker` |
| 이벤트 컨슈머 | `com.serverarchitecture.enrollment.EnrollmentConsumer` | `Server/.../bin/EnrollmentConsumer` |
| 콘솔 | `com.serverarchitecture.client.ConsoleClient` | `Client/build/install/Client/bin/Client`, 또는 `./gradlew :Client:run -q --console=plain` |

## 3. Proto 계약 (확정, Proto/src/main/proto)

- `common.proto`: `Role { STUDENT, PROFESSOR, SERVICE }`, `StudentInfo`, `CourseInfo`, `OfferingTime`, `OfferingInfo`
- `auth.proto`: `AuthService.Login(LoginRequest) returns (LoginResponse{token, user_id, name, role})`
- `academic.proto`: `AcademicService`
  - `ListStudents`, `ListCourses`, `GetStudent`, `AddStudent`, `DeleteStudent`, `AddCourse`, `DeleteCourse`
  - `ListCoursesByStudent`, `ListStudentsByCourse`, `ListCompletedCourses`, `GetCatalog(semester)`
- `enrollment.proto`: `EnrollmentService`
  - `EnterQueue`, `GetQueueStatus`, `WatchQueue` (서버 스트리밍, 새로 추가), `LeaveQueue`, `GetSchedule`
  - `ListOfferings`, `Enroll`, `CancelEnrollment`, `ListMyEnrollments`
  - `EnrollmentEvent`(Kafka 페이로드)는 그대로
- `data.proto` (패키지 `serverarchitecture.data`, Java `com.serverarchitecture.data.api`): `DataService`
  - `Item { id, map<string,string> fields }`
  - `Create`, `Read`, `ReadAll`, `ReadWhere(field, value)`, `Update(expected)`, `Upsert(expected)`, `Delete`, `Count`

공통 규칙:
- 실패는 응답 필드가 아니라 gRPC 상태로 보낸다. 설명(description)은 콘솔에 그대로 찍히는 한국어 문장이다. 응답에 `success` 플래그는 없다.
- 기존 `ServerClientService`와 `google.api.http` 주석은 삭제했다.

### 3.1 Data API 의미 (A가 구현, B가 사용)

| RPC | 동작 |
|---|---|
| Create | id가 비면 INVALID_ARGUMENT, 이미 있으면 ALREADY_EXISTS, 성공 시 저장된 Item 반환 |
| Read | `found=false`면 item 비어 있음. 모르는 컬렉션도 not found |
| ReadAll | 생성 순서(최초 Create 또는 Upsert 생성 순서). 재시작 후에도 같은 순서 |
| ReadWhere | `fields[field] == value`인 항목, 생성 순서. 필드가 없으면 ""로 본다 |
| Update | 없으면 NOT_FOUND. `expected`의 각 키가 현재 값과 다르면 FAILED_PRECONDITION. 맞으면 fields 전체 교체 |
| Upsert | 없으면 생성(CREATED, expected 무시). 있고 expected가 비었거나 일치하면 교체(UPDATED). 불일치면 UNCHANGED와 현재 Item |
| Delete | `deleted` 여부 |
| Count | 항목 수. 모르는 컬렉션은 0 |

- 컬렉션은 첫 쓰기 때 생긴다. 컬렉션 이름은 `[a-z0-9_]+`만 허용(아니면 INVALID_ARGUMENT).
- 한 컬렉션 안의 연산은 선형화 가능(컬렉션별 잠금). 쓰기는 파일 기록이 끝난 뒤 응답한다.

### 3.2 서비스 간 인증

- Envoy 밖에서 오는 요청은 로그인 JWT(Bearer)로 `AuthInterceptor`가 검사한다. 권한표는 각 `*Server.java`의 `PERMISSIONS`에 있다.
- 수강신청 서비스가 학사 서비스를 부를 때는 `JwtProvider.issueService("enrollment-service")`로 만든 `Role.SERVICE` 토큰을 `BearerCredentials`로 붙인다(`AcademicClient`에 구현됨). 로그인으로는 SERVICE 토큰이 나오지 않는다.
- 학생(STUDENT)이 학사 서비스의 `ListCoursesByStudent`, `ListCompletedCourses`를 부르면 자기 학번만 허용한다(다르면 PERMISSION_DENIED). B1이 서비스 구현 안에서 검사한다.

### 3.3 NullDataException

`com.serverarchitecture.NullDataException extends CustomException`(unchecked), 상태 NOT_FOUND. 반드시 있어야 하는 데이터가 없을 때 Server 쪽에서 던진다. 예: `DataClient.require(...)`가 not found, 과목의 선수과목 id가 없는 과목, 학사 서비스 `GetStudent`가 NOT_FOUND를 돌려줘 `AcademicClient.getStudent`가 다시 던질 때. 각 서비스의 응답 처리기는 `CustomException`을 `toStatus()`로 바꿔 보낸다(수강신청 서비스의 `respond`에 이미 있음, 학사와 로그인도 같은 방식으로).

## 4. 포트와 환경 변수

| 대상 | 포트 | 환경 변수(기본값) |
|---|---|---|
| Envoy | 8080, admin 9901 | |
| Data | 9200 | `DATA_PORT`(9200), `DATA_DIR`(data-store), `DATA_FSYNC`(always / batch / never, 기본 always) |
| auth | 9101 | `AUTH_PORT`(9101) |
| academic | 9102 | `ACADEMIC_PORT`(9102) |
| enrollment | 9103 | `ENROLLMENT_PORT`(9103), `ACADEMIC_HOST`(localhost), `ACADEMIC_PORT`(9102), `ACADEMIC_DEADLINE_MS`(3000), `WATCH_INTERVAL_MS`(1000, B2가 EnrollmentConfig에 추가) |
| Server 공통 | | `JWT_SECRET`, `DATA_HOST`(localhost), `DATA_PORT`(9200), `DATA_DEADLINE_MS`(3000), `SEED_ON_START`(true), `LOG_DIR` |
| enrollment, QueueWorker, Consumer | | 기존 그대로: `REDIS_*`, `KAFKA_BOOTSTRAP`, `SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS`, `PASS_CLAIM_SECONDS`, `WAITER_TIMEOUT_SECONDS`, `POLL_INTERVAL_MS`, `WORKER_TICK_MS`, `CATALOG_REFRESH_SECONDS` |
| Client | | `SERVER_HOST`(localhost), `SERVER_PORT`(8080) |

- 값은 `ServiceConfig`(Server 공통)와 `EnrollmentConfig`(수강신청)에서 읽는다.
- 없어진 변수: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_TIMEOUT_MS`.
- compose에서는 같은 서비스의 두 복제본이 컨테이너만 다르고 포트는 같다. 한 호스트에서 컨테이너 없이 두 개를 띄울 때만 `*_PORT`를 바꾼다.
- compose에서 enrollment의 `ACADEMIC_HOST=envoy`, `ACADEMIC_PORT=8080`으로 두어 Envoy가 academic 두 복제본에 분산한다.

## 5. 컬렉션과 필드 (`DataCollections` 상수)

모든 값은 문자열이다. 목록은 쉼표로 잇는다(빈 목록은 "").

| 컬렉션 | id | 필드 | 쓰는 쪽 |
|---|---|---|---|
| `users` | 사용자 id(학번, 교수 id) | `name`, `role`(STUDENT / PROFESSOR), `password_hash` | auth(읽기), academic(학생 추가/삭제 시 쓰기) |
| `students` | 학번 | `name`, `department`, `max_credits`, `completed_course_ids` | academic |
| `courses` | 과목 id | `name`, `instructor_name`, `credits`, `prerequisite_course_ids` | academic |
| `offerings` | 개설 강의 id(정수 문자열) | `course_id`, `semester`, `section`, `professor_id`, `capacity`, `room`, `times` | academic(읽기), QueueWorker(읽기) |
| `enrollments` | `학번:개설강의id` | `student_id`, `offering_id`, `semester`, `status`(ENROLLED / CANCELLED), `applied_at_ms` | EnrollmentConsumer(쓰기), academic, QueueWorker(읽기) |

- `times` 형식: `MON 09:00-10:30,WED 09:00-10:30`(요일은 MON~SUN).
- `password_hash` 형식: `pbkdf2$<반복수>$<salt base64>$<key base64>`(패딩 없음, PBKDF2WithHmacSHA256, 100000회, salt 16바이트, 키 256비트). `PasswordHasher`에 구현됨.
- 이수 과목은 `students.completed_course_ids`에 둔다(예전의 2026-1 개설 강의와 COMPLETED 행은 없앴다).
- `enrollments`는 Kafka를 거쳐 비동기로 채워진다. 실시간 기준은 Redis이고, 학사 서비스의 "학생별 수강과목", "과목별 수강학생"은 몇 초 늦을 수 있다.

## 6. 시드

- 위치: `Server/src/main/resources/seed/<컬렉션>.tsv` (users, students, courses, offerings). V2 SQL과 V3, V5의 내용을 옮겼다.
- 형식: UTF-8 TSV. 첫 줄은 `id` 다음 필드 이름들, 이후 한 줄에 한 항목. 빈 칸은 "".
- 내용: 학생 18명(V2의 17명 + 발표자 60220435), 교수 P001~P005, 과목 7개(학점 3), 2026-2 개설 강의 8개(id 8~15, 예전 SERIAL 값 그대로), 이수 기록 65건.
- 비밀번호는 모두 아이디와 같다. `SeedLoaderTest`가 23명 전원의 해시를 검증한다.
- 적재: `SeedLoader.seedWithRetry(data, collections...)`가 컬렉션마다 `seedIfEmpty`를 부르고, Data 서버가 아직 없으면(UNAVAILABLE, DEADLINE_EXCEEDED) 1초 간격으로 최대 30번 다시 시도한다. 컬렉션이 비었을 때만 Create하고, 두 복제본이 동시에 넣어 생기는 ALREADY_EXISTS는 무시한다. `SEED_ON_START=false`면 건너뛴다.
  - auth는 시작 시 `users`
  - academic은 `students`, `courses`, `offerings`
- `enrollments`는 시드가 없다.

## 7. Envoy 라우팅 (D)

- 리스너 8080, `codec_type: AUTO`, HTTP/2 upstream. grpc_web, grpc_json_transcoder, cors 필터는 뺀다. descriptor(.pb)도 더는 필요 없다(Proto 빌드에서 descriptor 생성을 껐다).
- 라우트, 위에서부터:
  1. `path: /serverarchitecture.EnrollmentService/WatchQueue` → `enrollment`. `timeout: 0s`, 라우트 `idle_timeout: 300s`, `max_stream_duration` 없음(스트림이 끊기지 않게).
  2. `prefix: /serverarchitecture.AuthService/` → `auth`
  3. `prefix: /serverarchitecture.AcademicService/` → `academic`
  4. `prefix: /serverarchitecture.EnrollmentService/` → `enrollment`
  - 2~4는 timeout 5s, 기존 retry_policy(connect-failure, refused-stream)
- 클러스터 셋, 각각 STRICT_DNS ROUND_ROBIN 엔드포인트 둘: `auth1/auth2:9101`, `academic1/academic2:9102`, `enrollment1/enrollment2:9103`. 기존 outlier_detection 유지.
- Data 서버는 Envoy에 노출하지 않는다(내부망만).

## 8. 2단계 분담과 파일 소유

규칙: 자기 소유 경로만 수정한다. 아래 "공유(고정)" 파일은 1단계에서 확정했으며 아무도 고치지 않는다. 꼭 바꿔야 하면 수정하지 말고 보고에 적는다. 주석 금지, 커밋은 사용자 이름으로 각자 브랜치 위에서, push 금지.

### 공유(고정)
- `settings.gradle`, `build.gradle`, `*/build.gradle`
- `Proto/**`, `Common/**`
- `Server/src/main/java/com/serverarchitecture/*.java`(최상위: `AuthInterceptor`, `AccessLogInterceptor`, `JwtProvider`, `PasswordHasher`, `User`, `AuthenticatedUser`, `CustomException`, `NullDataException`, `DataClient`, `DataCollections`, `SeedLoader`, `ServiceConfig`, `GrpcServerRunner`, `BearerCredentials`)
- `Server/src/main/java/com/serverarchitecture/redis/**`, `pool/**`와 그 테스트
- `Server/src/test/java/com/serverarchitecture/*.java`(최상위 테스트)

### A: Data 서버
- 소유: `Data/**`(Dockerfile 제외)
- 할 일
  - `Store`: 컬렉션별 메모리 맵(생성 순서 유지) + 컬렉션별 append-only 로그 파일(`DATA_DIR/<컬렉션>.log`). 시작 시 재생, 손상된 마지막 줄은 버리고 경고. 압축(compaction)은 선택.
  - fsync 정책: `always`(쓰기마다 force), `batch`(짧은 주기로 모아서 force, 응답은 기록 후), `never`. 레코드 형식은 A가 정하되 탭, 줄바꿈 등이 들어간 값도 안전해야 한다(예: Item protobuf 바이트를 길이 접두사로 기록하거나 base64 한 줄).
  - `DataServiceImpl`: 3.1의 의미 그대로, 잘못된 입력은 INVALID_ARGUMENT.
  - 테스트: Store 단위(재시작 후 재생, 동시 쓰기, expected 처리), in-process gRPC 테스트.
- `DataServer.main`의 시그니처와 환경 변수 이름은 유지한다.

### B1: 로그인 + 학사 + 시드
- 소유: `Server/src/main/java/com/serverarchitecture/auth/**`, `.../academic/**`, `Server/src/main/resources/seed/**`, `Server/src/test/java/com/serverarchitecture/auth/**`, `.../academic/**`
- 할 일
  - `AuthServiceImpl.login`: `UserRepository`(Data `users`)로 조회, `PasswordHasher.matches`. 없는 사용자도 더미 해시로 같은 시간을 쓴다. 실패는 UNAUTHENTICATED "아이디 또는 비밀번호가 올바르지 않습니다."
  - `AuthServer`: `SEED_ON_START`면 `users` 시드.
  - `AcademicServiceImpl` 11개 RPC와 Data 기반 저장소(`StudentRepository`, `CourseRepository`, `OfferingRepository`, 필요하면 `EnrollmentRepository`). `Student`, `Course` 모델은 자유롭게 고친다.
  - 판단
    - AddStudent: 중복이면 ALREADY_EXISTS. 이수 과목 id가 없으면 NullDataException. `users`(비밀번호=학번 해시)와 `students` 둘 다 만든다. `max_credits`가 0이면 18.
    - DeleteStudent: 없으면 NullDataException. ENROLLED 신청이 있으면 FAILED_PRECONDITION. 아니면 `students`, `users` 삭제.
    - AddCourse: 중복이면 ALREADY_EXISTS. 선수과목이 없으면 NullDataException. `credits`가 0이면 3.
    - DeleteCourse: 없으면 NullDataException. 개설 강의가 있으면 FAILED_PRECONDITION.
    - ListCoursesByStudent, ListCompletedCourses: 학생이 없으면 NullDataException. 학생 본인 검사(3.2).
    - ListStudentsByCourse: 과목이 없으면 NullDataException.
    - GetCatalog: `offerings`(semester) + `courses` + `users`(교수 이름) + ENROLLED 수로 `remaining=capacity-신청수`. 개설 강의가 하나도 없으면 NullDataException.
  - `AcademicServer`: `SEED_ON_START`면 `students`, `courses`, `offerings` 시드. `PERMISSIONS`는 그대로 쓴다.
  - 테스트: in-process gRPC로 가짜 DataService를 띄우거나, Data 모듈 없이 `DataServiceImplBase`를 메모리로 구현한 테스트 더블 사용(Server는 Data 모듈에 의존하지 않는다).

### B2: 수강신청 + 스트리밍 + 컨슈머 + 워커
- 소유: `Server/src/main/java/com/serverarchitecture/enrollment/**`, `Server/src/main/resources/redis/**`, `Server/src/test/java/com/serverarchitecture/enrollment/**`
- 이미 된 것: `AcademicClient`(서비스 토큰), `OfferingCatalog`가 `GetCatalog`를 사용, `EnrollmentServiceImpl`이 실패를 `CustomException`으로 던짐, QueueWorker 프리웜이 Data에서 읽음, 컨슈머가 Data에 Upsert/Update.
- 할 일
  - `WatchQueue`: 첫 상태를 바로 보내고, `WATCH_INTERVAL_MS`마다 순번을 확인해 바뀔 때만 보낸다. WAITING인 동안 매 확인마다 `markSeen`(열린 스트림 = 하트비트). ADMITTED 또는 NOT_IN_QUEUE를 보내면 onCompleted. 클라이언트 취소(`ServerCallStreamObserver.setOnCancelHandler`) 시 정리. 스트림마다 스레드를 만들지 말고 공유 ScheduledExecutor 하나.
  - 검사 순서 확인과 보완: 학생 유효(`GetStudent` NOT_FOUND → NullDataException), 강의 존재(NOT_FOUND), 선수과목, 중복, 시간 충돌, 같은 과목, 학점, 정원. 상태 코드와 문구 정리.
  - 컨슈머: 멱등(`학번:개설강의id` 키), 저장 후 커밋, 재시도와 DLT 유지 확인. Data 장애(UNAVAILABLE, DEADLINE_EXCEEDED)는 재시도 대상.
  - 테스트: `EnrollmentRulesTest` 유지, WatchQueue와 컨슈머 apply 단위 테스트(가짜 Data, 가짜 Academic in-process).

### C: 콘솔
- 소유: `Client/**`(Dockerfile 제외)
- 할 일
  - 로그인(아이디, 비밀번호) → `AuthService.Login` → 토큰을 Bearer로 붙이는 자체 `CallCredentials`(Client는 Server 모듈에 의존하지 않는다).
  - `switch`-`case` 메뉴 루프, `X`로 종료. 역할별 메뉴
    - 교수: 학생 목록, 과목 목록, 학생별 수강과목, 과목별 수강학생, 학생별 이수과목, 학생 추가, 학생 삭제, 과목 추가, 과목 삭제, X 종료
    - 학생: 과목 목록, 내 수강과목, 내 이수과목, 수강신청, 수강취소, 내 신청 목록, X 종료
  - 수강신청: `GetQueueStatus`가 ADMITTED가 아니면 `EnterQueue` 후 `WatchQueue`를 받으며 "앞에 N명"(position-1) 출력 → ADMITTED면 `ListOfferings` 표 → 개설 강의 id 입력 → `Enroll` 결과 또는 상태 설명 출력. 수강취소, 내 신청 목록도 입장권이 없으면 같은 대기 과정을 거친다.
  - 모든 gRPC 오류는 `[오류] <description>`으로 찍고 메뉴로 돌아간다. 종료 시 입장 상태면 `LeaveQueue`.
  - 테스트: 입력 파싱, 표 출력 같은 순수 로직 단위 테스트.

### D: 배포
- 소유: `deploy/**`, 루트 `docker-compose.yml`, `Server/Dockerfile`, 새 `Data/Dockerfile`, 새 `Client/Dockerfile`(선택), `deploy/README.md`
- 할 일
  - Dockerfile: 빌드 단계에 `Common`, `Data`, `Client` 디렉터리 복사 추가. Server 이미지 하나에서 `ENTRYPOINT`를 서비스별 `bin/AuthServer` 등으로 바꿔 쓴다. Data 이미지는 볼륨 `/app/data`(`DATA_DIR`).
  - 루트 `docker-compose.yml`(로컬 올인원): data, auth1/2, academic1/2, enrollment1/2, queue-worker, enrollment-consumer, envoy, redis, kafka, kafka-init. postgres 제거. 시작 순서 data → auth/academic → enrollment → worker/consumer.
  - `deploy/allinone/envoy.yaml`, `deploy/lb/envoy.yaml`을 7장 라우팅으로. `deploy/envoy/Dockerfile`은 descriptor 단계 제거(평범한 envoy 이미지 + 설정).
  - `deploy/app`, `deploy/db`, `deploy/infra`, `deploy/lb` compose를 새 프로세스 구성에 맞춘다(db VM에는 Data 서버). 파일만 바꾸고 VM에는 배포하지 않는다.
  - `deploy/allinone/e2e-local.sh`: 로컬 compose를 올리고 grpcurl(`-import-path Proto/src/main/proto -proto ...`, `-plaintext`)로 로그인, 학사 조회, 대기열 진입, WatchQueue, 신청, 취소를 확인하고, 콘솔이 준비되면 표준입력으로 콘솔 시나리오 하나를 돌린다.

### 순서와 의존
- A, B1, B2, C, D는 동시에 시작한다. 계약(proto, Data 의미, 컬렉션, 환경 변수)은 위 내용으로 고정이다.
- 통합 확인(로컬 compose, e2e 스크립트)은 A, B1, B2, C가 끝난 뒤 D가 돌린다.

## 9. 1단계에서 남긴 것 (모두 2단계에서 구현됨)
- `AuthServiceImpl`, `AcademicServiceImpl`은 RPC 구현이 없어 UNIMPLEMENTED를 돌려준다. 저장소는 생성자만 있다. `UserRepository.findById`는 UnsupportedOperationException.
- `Store`(Data)는 모든 메서드가 UnsupportedOperationException, `DataServiceImpl`은 UNIMPLEMENTED.
- `WatchQueue`는 UNIMPLEMENTED. `ConsoleClient`는 시작하면 UnsupportedOperationException.
- ARCHITECTURE.md, README, demo/ 문서와 부하 테스트 스크립트는 아직 예전 구조를 설명한다(나중에 정리).

## 10. 3단계 통합 결과 (2026-10-23)

### 병합
- `feature/3t-data`, `feature/3t-b1`, `feature/3t-b2`, `feature/3t-console`, `feature/3t-deploy`를 `--no-ff`로 차례로 병합했다. 충돌은 없었다.

### 후속 정리
- `auth/Responder`를 최상위 `GrpcResponder`로 옮겼다. 기본 변환(CustomException, Data 오류)과 서비스별 변환 함수를 받는 형태라, 수강신청 서비스도 자기 변환(Redis 오류, 풀 고갈)을 넘겨 같은 응답기를 쓴다.
- `UserRepository`를 최상위로 옮겨 academic → auth 패키지 의존을 없앴다.
- 시작 시드 재시도(`auth/StartupSeeder`)를 `SeedLoader.seedWithRetry`로 합쳤다.
- `OfferingCatalog.start`가 학사 서비스가 준비될 때까지 0.5초부터 최대 10초까지 백오프로 다시 시도하고, 성공한 뒤 `CATALOG_REFRESH_SECONDS`마다 갱신한다(`OfferingCatalogTest`). 실제 스택에서 academic 두 개를 멈추고 enrollment1을 재시작해 500, 1000, 2000, 4000, 8000ms 재시도 뒤 개설 강의 8개를 읽는 것을 확인했다.
- 시작 순서: compose의 TCP 헬스체크(auth, academic은 시드 후에 포트를 연다), `depends_on: service_healthy`, `restart: unless-stopped`, 서비스 쪽 재시도(시드, 개설 강의, QueueWorker 프리웜, consumer 백오프)로 받친다. 변경 없이 그대로 두었다.
- 루트 `.gitignore`에 `.env`를 추가했다(`.dockerignore`와 `deploy/.gitignore`에는 이미 있음).
- e2e 거절 코드를 정확히 검사한다: 중복 ALREADY_EXISTS, 시간 충돌 ABORTED, 선수과목 ABORTED, 정원 RESOURCE_EXHAUSTED, 입장권 없음 FAILED_PRECONDITION. 콘솔은 FAILED_PRECONDITION만 입장권 만료로 보고 나머지는 설명을 그대로 찍으므로 그대로 맞는다.
- e2e에 같은 학생의 충돌 신청, Data 서버 재시작, 복제본 장애 검사를 더했다.
- 예전 구조의 죽은 코드: Server와 Proto에서 트랜스코딩, JDBC, Flyway 흔적을 찾았지만 1단계에서 이미 지워져 남은 것이 없었다. Proto 빌드 스크립트의 빈 줄만 정리했다.

### 검증
- `./gradlew build`: 178개 통과(Data 31, Server 122, Client 14, Common 11). Server의 Redis 통합 테스트 24개는 `REDIS_IT_PORT`로 Redis 8.10.2 컨테이너를 붙여 함께 통과했다(없으면 건너뜀).
- `deploy/allinone/e2e-local.sh`: 26개 통과, 0개 실패([docs/e2e/e2e-local.txt](e2e/e2e-local.txt)).
- 콘솔: [docs/e2e/console-student.txt](e2e/console-student.txt), [docs/e2e/console-professor.txt](e2e/console-professor.txt).

### 남은 일
- VM(sa-lb, sa-app1, sa-app2, sa-db, sa-infra) 배포와 부하 테스트. compose 파일만 바꿨다.
- Data 서버 단일 인스턴스(복제 없음), Redis와 Kafka 단일 인스턴스.
- Redis 차감 후 Kafka 기록 전 서버 다운 시 좌석만 줄어드는 문제를 맞출 대조 작업.
- 학점 한도 초과(OUT_OF_RANGE)와 DLT 경로는 실제 스택에서 확인하지 않았다(단위 테스트만).
- 콘솔 시작 시 로컬 JDK(26)가 Netty의 `sun.misc.Unsafe` 사용 경고를 stderr에 찍는다.
- `web/`, `demo/`(React, REST 기반 k6 스크립트)와 `DB_REQUIREMENTS.md`, `DEV_NOTES.md`는 예전 구조 기준이라 맞지 않는다. 손대지 않았다.

