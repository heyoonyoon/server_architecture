# 배포 가이드

## 구성

| VM | IP | 컨테이너 | compose 파일 |
|---|---|---|---|
| sa-lb | 192.168.64.30 | nginx, nginx-exporter, envoy | `deploy/lb/docker-compose.yml` |
| sa-app1 | 192.168.64.31 | server | `deploy/app/docker-compose.yml` |
| sa-app2 | 192.168.64.32 | server | `deploy/app/docker-compose.yml` |
| sa-db | 192.168.64.33 | postgres, postgres-exporter | `deploy/db/docker-compose.yml` |
| sa-infra | 192.168.64.34 | redis, redis-exporter, kafka, kafka-init, queue-worker, enrollment-consumer, prometheus, grafana | `deploy/infra/docker-compose.yml` |

```
브라우저 ──▶ sa-lb nginx :80 ──/api──▶ sa-lb envoy :8080 ──┬──▶ sa-app1 server :8081 ──┐
                                                            └──▶ sa-app2 server :8081 ──┴──▶ sa-db postgres :5432
                                                                         │
                                                         sa-infra redis :6379, kafka :9092
                                                                         │
                                          sa-infra queue-worker (대기열 입장), enrollment-consumer (Kafka → postgres)
```

## VM별 배포

각 VM의 `~/sa` 에 저장소 전체를 복사한 뒤 해당 역할의 compose 파일을 실행합니다. 앱 서버가 Redis 와 Kafka 를 사용하므로 순서는 db → infra → app1, app2 → lb 를 권장합니다.

```bash
for h in sa-lb sa-app1 sa-app2 sa-db sa-infra; do
  rsync -av --delete --exclude .git --exclude build --exclude .gradle --exclude node_modules --exclude dist --exclude logs ./ $h:~/sa/
done
```

```bash
ssh sa-db    'cd ~/sa && docker compose -f deploy/db/docker-compose.yml up -d'
ssh sa-infra 'cd ~/sa && mkdir -p deploy/infra/logs/queue-worker deploy/infra/logs/enrollment-consumer && sudo chown -R 10001:10001 deploy/infra/logs && docker compose -f deploy/infra/docker-compose.yml up -d --build'
ssh sa-app1  'cd ~/sa && mkdir -p deploy/app/logs && sudo chown 10001:10001 deploy/app/logs && SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-app2  'cd ~/sa && mkdir -p deploy/app/logs && sudo chown 10001:10001 deploy/app/logs && SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-lb    'cd ~/sa && docker compose -f deploy/lb/docker-compose.yml up -d --build'
```

sa-infra 는 앱 서버와 같은 `sa-server` 이미지를 빌드하여 queue-worker 와 enrollment-consumer 를 실행합니다. queue-worker 는 앱 서버가 Flyway 로 수강신청 테이블(V5)을 만들 때까지 기다렸다가 시작하므로 infra 를 앱 서버보다 먼저 올려도 됩니다.

sa-app1 과 sa-app2 는 같은 compose 파일을 그대로 사용합니다. `SERVER_NAME` 은 컨테이너 호스트 이름이 되어 접근 로그의 `server=` 값으로 남습니다. 지정하지 않으면 `sa-app` 이 됩니다.

DB 스키마와 초기 데이터는 서버가 시작할 때 Flyway 로 적용합니다. 마이그레이션 파일은 `Server/src/main/resources/db/migration` 에 있고, 비밀번호 해시를 만들어야 하는 V3 (`users`, `professor` 테이블 생성과 계정 등록) 만 Java 마이그레이션 `Server/src/main/java/db/migration/V3__create_users.java` 입니다. 적용 이력은 `flyway_schema_history` 테이블에 남습니다. 이미 적용된 버전은 다시 실행하지 않으며, sa-app1 과 sa-app2 가 동시에 시작해도 Flyway 가 PostgreSQL 락으로 한 서버만 적용하게 합니다. DB 이름이나 계정을 바꾼 뒤에는 `docker compose -f deploy/db/docker-compose.yml down -v` 로 볼륨을 지우고 다시 올린 뒤 앱 서버를 재시작합니다.

스키마를 바꿀 때는 이미 적용된 파일을 수정하지 않고 다음 번호로 새 파일을 추가합니다. 적용된 파일을 수정하면 체크섬이 달라져 서버가 시작되지 않습니다.

```
Server/src/main/resources/db/migration/V4__add_course_credit.sql
```

```sql
ALTER TABLE course ADD COLUMN credit INT NOT NULL DEFAULT 3;
```

파일을 추가한 뒤 앱 VM 에서 `docker compose -f deploy/app/docker-compose.yml up -d --build` 로 다시 빌드하면 시작할 때 V4 가 적용됩니다.

## 설정 변경

IP나 계정을 바꾸려면 각 compose 파일과 같은 디렉터리에 `.env` 파일을 두고 값을 덮어씁니다.

| 파일 위치 | 변수 | 기본값 |
|---|---|---|
| `deploy/app/.env` | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `192.168.64.33`, `5432`, `serverarchitecture`, `serverarchitecture`, `serverarchitecture` |
| `deploy/app/.env` | `JWT_SECRET` | 개발용 기본 키 `serverarchitecture-dev-jwt-secret-change-me` |
| `deploy/app/.env` | `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP` | `192.168.64.34`, `6379`, `192.168.64.34:9092` |
| `deploy/app/.env` | `SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS`, `POLL_INTERVAL_MS` | `2026-2`, `50`, `300`, `2000` |
| `deploy/app/.env` | `DB_POOL_MAX`, `DB_POOL_TIMEOUT_MS` | `30`, `3000` (HikariCP 최대 연결 수, 연결 대기 한도 ms) |
| `deploy/app/.env` | `REDIS_POOL_MAX`, `REDIS_POOL_MIN_IDLE`, `REDIS_POOL_MAX_WAIT_MS` | `128`, `8`, `2000` (Jedis 풀 최대/최소 유휴 연결 수, 대기 한도 ms) |
| `deploy/app/.env` | `CATALOG_REFRESH_SECONDS` | `60` (개설 강의 메모리 캐시 갱신 주기) |
| `deploy/db/.env` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `serverarchitecture` |
| `deploy/infra/.env` | `INFRA_HOST`, `KAFKA_CLUSTER_ID`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | `192.168.64.34`, 고정 ID, `admin`, `admin` |
| `deploy/infra/.env` | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `192.168.64.33`, `5432`, `serverarchitecture`, `serverarchitecture`, `serverarchitecture` |
| `deploy/infra/.env` | `SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS`, `WORKER_TICK_MS` | `2026-2`, `50`, `300`, `500` |
| `deploy/infra/.env` | `PASS_CLAIM_SECONDS`, `WAITER_TIMEOUT_SECONDS` | `30`, `30` |
| `deploy/infra/.env` | `DB_POOL_MAX`, `DB_POOL_TIMEOUT_MS` | `2`, `3000` (queue-worker, enrollment-consumer 각각) |
| `deploy/infra/.env` | `REDIS_POOL_MAX`, `REDIS_POOL_MIN_IDLE`, `REDIS_POOL_MAX_WAIT_MS` | `8`, `1`, `2000` |

`SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS` 는 앱 VM 과 sa-infra 에 같은 값을 지정해야 합니다. `PASS_CLAIM_SECONDS`, `WAITER_TIMEOUT_SECONDS` 는 queue-worker 만 읽습니다.

PostgreSQL `max_connections` 는 100 입니다. 기본값 기준 앱 서버 2대 x 30 + queue-worker 2 + enrollment-consumer 2 = 64 연결이므로, `DB_POOL_MAX` 를 올릴 때는 합계가 100 을 넘지 않게 맞춥니다. Redis 나 DB 연결을 대기 한도 안에 얻지 못하면 무한 대기하지 않고 gRPC `UNAVAILABLE` ("요청이 많아 처리하지 못했습니다...") 로 바로 실패합니다.

개설 강의 정보(과목, 학점, 교수, 시간, 강의실, 선수과목, 정원)는 앱 서버가 시작할 때 한 번 읽어 메모리에 두고 `CATALOG_REFRESH_SECONDS` 마다 다시 읽습니다. 남은 자리는 요청마다 Redis `seats:<학기>` 해시에서 읽습니다. 교수가 과목을 추가하거나 삭제하면 그 요청을 처리한 앱 서버는 바로 다시 읽고, 다른 앱 서버는 다음 갱신 주기에 반영합니다. DB 에서 개설 강의를 직접 바꾼 경우 최대 `CATALOG_REFRESH_SECONDS` 뒤에 반영되며, 바로 반영하려면 앱 서버를 다시 시작합니다.

동시 입장 인원도 Redis 의 `max:<학기>` 키(정수)로 바꿀 수 있습니다. queue-worker 가 주기마다 읽으므로 다시 시작할 필요가 없고, 키가 없으면 `QUEUE_MAX_ACTIVE` 를 씁니다. 줄이면 이미 입장한 학생은 그대로 두고, 입장 인원이 새 값 아래로 내려갈 때까지 새로 입장시키지 않습니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis redis-cli SET max:2026-2 100'
```

수강신청 시작 시각은 환경 변수가 아니라 Redis 의 `open:<학기>` 키(epoch 밀리초)에 둡니다. 서버를 다시 시작하지 않고 바로 바뀝니다. 키가 없으면 언제든 대기열에 들어갈 수 있습니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis redis-cli SET open:2026-2 1792040400000'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis redis-cli DEL open:2026-2'
```

다음 값은 환경 변수가 아니라 파일에 직접 적혀 있습니다.

| 파일 | 내용 |
|---|---|
| `deploy/lb/envoy.yaml` | `grpc_servers` 클러스터 엔드포인트 `192.168.64.31:8081`, `192.168.64.32:8081` (STATIC), CORS 허용 Origin |
| `deploy/infra/prometheus/prometheus.yml` | Prometheus 수집 대상 IP |

Envoy 는 설정 파일 안에서 환경 변수를 치환하지 않으므로 앱 VM IP가 바뀌면 `deploy/lb/envoy.yaml` 을 수정한 뒤 `docker compose -f deploy/lb/docker-compose.yml restart envoy` 를 실행합니다.

## 로그인과 권한

로그인(`LoginController`)을 제외한 모든 RPC 는 `authorization: Bearer <JWT>` 메타데이터가 있어야 합니다. 토큰은 HS256 으로 서명하며 2시간 동안 유효합니다.

| 계정 | 아이디 | 초기 비밀번호 | 역할 |
|---|---|---|---|
| 학생 17명 | 학번 (예: `20100124`) | 학번과 같음 | STUDENT |
| 교수 Park, Kim, Ko, Ahn, Lee | `P001` ~ `P005` | 아이디와 같음 | PROFESSOR |

새로 추가한 학생도 학번이 초기 비밀번호입니다. 비밀번호는 bcrypt (cost 12) 로 해시하여 알고리즘, cost, salt 가 포함된 하나의 문자열로 `users.password_hash` 에 저장합니다.

| 역할 | 호출 가능한 RPC |
|---|---|
| PROFESSOR | 학생 목록, 과목 목록, 학생 추가/삭제, 과목 추가/삭제, 수강신청 일정 |
| STUDENT | 과목 목록, 대기열 진입, 조회, 나가기, 수강신청 일정, 개설 강의 목록, 수강신청, 수강취소, 내 신청 목록 |

토큰이 없거나 만료, 위조되면 `UNAUTHENTICATED`, 역할이 맞지 않으면 `PERMISSION_DENIED` 를 반환합니다.

두 앱 서버는 같은 `JWT_SECRET` 을 사용해야 한쪽에서 발급한 토큰을 다른 쪽에서 검증할 수 있습니다. 실제 배포에서는 32자 이상의 임의 값을 두 앱 VM 에 똑같이 지정합니다. 지정하지 않으면 개발용 기본 키를 사용하고 시작 로그에 경고를 남깁니다.

```bash
openssl rand -base64 48
ssh sa-app1 'cd ~/sa && JWT_SECRET=<생성한 값> SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d'
ssh sa-app2 'cd ~/sa && JWT_SECRET=<생성한 값> SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d'
```

## 로그

앱 서버는 컨테이너의 `/app/logs` 에 로그를 쓰고, 이 디렉터리는 앱 VM 의 `~/sa/deploy/app/logs` 에 마운트됩니다. 컨테이너는 UID 10001 로 실행되므로 이 디렉터리의 소유자를 10001 로 맞춰 둡니다. 단일 머신 실행에서는 저장소 루트의 `logs/server1`, `logs/server2` 에 남습니다.

| 파일 | 내용 |
|---|---|
| `access.log` | 사용자별 시스템 사용 기록 (RPC 한 번에 한 줄) |
| `server.log` | 서버 시작, Flyway, HikariCP 등 애플리케이션 로그 |

두 파일 모두 자정(Asia/Seoul)마다 `access.2026-10-15.log` 처럼 날짜를 붙여 넘기고 30일치를 보관합니다.

`access.log` 형식은 다음과 같습니다.

```
2026-10-15 01:52:03.118 | server=sa-app1 | user=20100123   | command=LOGIN          | result=OK                |    85ms
2026-10-15 01:52:04.020 | server=sa-app1 | user=20100123   | command=ADD_STUDENT    | result=PERMISSION_DENIED |     0ms
```

| 필드 | 의미 |
|---|---|
| 시각 | 요청 처리가 끝난 시각 (Asia/Seoul, 밀리초) |
| `server` | 요청을 처리한 앱 서버 |
| `user` | 토큰의 사용자 ID, 로그인은 입력한 아이디, 알 수 없으면 `-` |
| `command` | `LOGIN`, `LIST_STUDENTS`, `LIST_COURSES`, `ADD_STUDENT`, `DELETE_STUDENT`, `ADD_COURSE`, `DELETE_COURSE` |
| `result` | `OK`, 로그인 실패 시 `LOGIN_FAILED`, 그 밖에는 gRPC 상태 코드 (`UNAUTHENTICATED`, `PERMISSION_DENIED`, `INTERNAL` 등) |
| 마지막 값 | 처리 시간 (ms) |

비밀번호와 토큰은 기록하지 않습니다.

```bash
ssh sa-app1 'tail -f ~/sa/deploy/app/logs/access.log'
```

## REST API

envoy 의 gRPC-JSON 트랜스코더가 REST/JSON 요청을 gRPC 호출로 바꿔 앱 서버에 전달합니다. 경로는 `Proto/src/main/proto/server_client.proto` 의 `google.api.http` 옵션에 정의되어 있으며 앱 서버 코드는 gRPC 그대로입니다.

| 메서드 | 경로 | RPC | 요청 본문 |
|---|---|---|---|
| POST | `/login` | `LoginController` | `{"userId": "P001", "password": "P001"}` |
| GET | `/students` | `GetStudentListController` | |
| POST | `/students` | `AddStudentController` | `{"studentId": "...", "name": "...", "department": "...", "completedCourseIds": []}` |
| DELETE | `/students/{studentId}` | `DeleteStudentController` | |
| GET | `/courses` | `GetCourseListController` | |
| POST | `/courses` | `AddCourseController` | `{"courseId": "...", "courseName": "...", "instructorName": "...", "prerequisiteCourseIds": []}` |
| DELETE | `/courses/{courseId}` | `DeleteCourseController` | |

브라우저는 nginx 를 거쳐 `http://192.168.64.30/api/...` 로, 그 밖에는 `http://192.168.64.30:8080/...` 로 호출합니다. JSON 필드 이름은 lowerCamelCase 이고 열거형은 `"PROFESSOR"` 처럼 이름으로 표시합니다. 로그인 외의 요청에는 `authorization: Bearer <JWT>` 헤더를 붙입니다.

```bash
TOKEN=$(curl -s -X POST http://192.168.64.30:8080/login -H 'content-type: application/json' -d '{"userId":"P001","password":"P001"}' | jq -r .token)
curl -s http://192.168.64.30:8080/students -H "authorization: Bearer $TOKEN"
curl -s -X DELETE http://192.168.64.30:8080/students/20100124 -H "authorization: Bearer $TOKEN"
```

gRPC 오류는 HTTP 상태 코드로 바뀌고 본문에 `code` 와 `message` 가 담깁니다. 토큰이 없으면 401, 권한이 없으면 403 입니다.

```json
{"code":7,"message":"권한이 없습니다.","details":[]}
```

트랜스코더는 proto 의 디스크립터 세트(`/etc/envoy/server_client.pb`)를 읽습니다. 이 파일은 `deploy/envoy/Dockerfile` 이 envoy 이미지를 빌드할 때 Gradle 로 생성하므로, proto 를 바꾼 뒤에는 앱 서버와 함께 envoy 이미지도 다시 빌드합니다.

```bash
ssh sa-lb 'cd ~/sa && docker compose -f deploy/lb/docker-compose.yml up -d --build envoy'
```

## 포트

| VM | 포트 | 용도 |
|---|---|---|
| sa-lb | 80 | nginx (정적 프론트엔드, `/api` → envoy) |
| sa-lb | 8080 | envoy (REST/JSON, gRPC-Web, 네이티브 gRPC) |
| sa-lb | 9901 | envoy admin, `/stats/prometheus` |
| sa-lb | 9113 | nginx-prometheus-exporter |
| sa-app1, sa-app2 | 8081 | gRPC 서버 (envoy 가 호출) |
| sa-db | 5432 | PostgreSQL |
| sa-db | 9187 | postgres-exporter |
| sa-infra | 6379 | Redis |
| sa-infra | 9121 | redis_exporter |
| sa-infra | 9092 | Kafka (외부 클라이언트용 EXTERNAL 리스너, 컨테이너 내부 9094) |
| sa-infra | 9090 | Prometheus |
| sa-infra | 3000 | Grafana |

## 실험 A / B

| 실험 | 브라우저 주소 | gRPC-Web 요청 경로 |
|---|---|---|
| A (단일 Origin) | http://192.168.64.30 | `http://192.168.64.30/api/...` → nginx 가 `/api` 를 제거하고 envoy 로 전달 |
| B (Envoy 직접 호출) | http://192.168.64.30 | `http://192.168.64.30:8080/...` → envoy 직접 호출, CORS 필요 |

실험 B 는 프론트엔드를 `VITE_API_BASE_URL=http://192.168.64.30:8080` 으로 빌드하여 사용합니다.

```bash
ssh sa-lb 'cd ~/sa && docker compose -f deploy/lb/docker-compose.yml build --build-arg VITE_API_BASE_URL=http://192.168.64.30:8080 nginx && docker compose -f deploy/lb/docker-compose.yml up -d nginx'
```

k6 는 `192.168.64.30:8080` 으로 네이티브 gRPC 를 호출합니다.

## 스케일아웃 실험 (앱 VM 1대 vs 2대)

envoy 는 두 앱 VM 에 라운드로빈으로 요청을 분산합니다. sa-app2 의 서버를 멈추면 envoy 는 연결 실패를 재시도하고 이상 감지(outlier detection)로 해당 엔드포인트를 제외하여 sa-app1 한 대로만 처리합니다.

```bash
ssh sa-app2 'cd ~/sa && docker compose -f deploy/app/docker-compose.yml stop'
```

이 상태에서 부하 테스트를 실행해 1대 결과를 얻고, 다시 시작한 뒤 같은 테스트로 2대 결과를 얻어 비교합니다.

```bash
ssh sa-app2 'cd ~/sa && docker compose -f deploy/app/docker-compose.yml start'
```

엔드포인트 상태는 `http://192.168.64.30:9901/clusters` 에서, 엔드포인트별 요청 수는 Grafana 에서 `envoy_cluster_upstream_rq_total` 등으로 확인합니다.

## 수강신청 대기열과 이벤트 처리

### 프로세스

세 프로세스 모두 같은 `sa-server` 이미지에 들어 있고 시작 스크립트만 다릅니다.

| 프로세스 | 실행 위치 | 시작 스크립트 (메인 클래스) | 개수 |
|---|---|---|---|
| 앱 서버 | sa-app1, sa-app2 | `/app/bin/Server` (`com.serverarchitecture.Server`) | 2 |
| 대기열 워커 | sa-infra `queue-worker` | `/app/bin/QueueWorker` (`com.serverarchitecture.QueueWorker`) | 반드시 1 |
| 이벤트 컨슈머 | sa-infra `enrollment-consumer` | `/app/bin/EnrollmentConsumer` (`com.serverarchitecture.EnrollmentConsumer`) | 1 |

시작 스크립트는 `Server/build.gradle` 의 `queueWorkerStartScripts`, `enrollmentConsumerStartScripts` 태스크가 만들고 `installDist` 결과의 `bin` 에 함께 들어갑니다. Flyway 마이그레이션은 앱 서버만 실행합니다.

### 흐름

1. 학생이 `GET /schedule` 로 시작 시각(`openAtMs`)과 서버 시각(`serverNowMs`)을 받아 카운트다운합니다. 시작 시각(`open:<학기>`) 전의 `POST /queue` 는 `FAILED_PRECONDITION` ("수강신청 진입은 HH:MM:SS부터 가능합니다.") 으로 거절됩니다.
2. 학생이 `POST /queue` 로 대기열(`queue:<학기>` ZSET)에 들어가고 `GET /queue` 를 `nextPollMs` 간격으로 호출해 순번을 확인합니다. 진입과 대기 중 조회 때마다 마지막 조회 시각이 `seen:<학기>` ZSET 에 기록됩니다.
3. queue-worker 가 `WORKER_TICK_MS` 마다 `redis/admit.lua` 를 실행합니다. 한 스크립트 안에서 원자적으로 다음을 처리합니다.
   - 만료된 입장권을 `active:<학기>` 에서 지웁니다.
   - `WAITER_TIMEOUT_SECONDS` 동안 조회가 없던 대기자를 대기열과 `seen:<학기>` 에서 지웁니다. 창을 닫았거나 연결이 끊긴 학생이 자리를 받지 않게 하기 위함입니다. 그 안에 다시 조회하면 순번이 그대로 유지되고, 지난 뒤에는 `GET /queue` 가 `NOT_IN_QUEUE` 를 돌려주므로 `POST /queue` 로 맨 뒤에 다시 들어가야 합니다.
   - 비어 있는 자리(`max:<학기>` 값, 없으면 `QUEUE_MAX_ACTIVE` - 현재 입장 인원)만큼 대기열 앞에서 꺼내 `PASS_CLAIM_SECONDS` 동안 유효한 짧은 입장권을 줍니다. 입장 후 첫 수강신청 API 호출(강의 목록, 신청, 취소, 내 신청 목록)이 입장권을 `PASS_TTL_SECONDS` 로 늘리고, 이후 호출마다 다시 늘립니다. 입장만 하고 오지 않은 학생의 자리는 `PASS_CLAIM_SECONDS` 뒤에 풀립니다.
4. 학생이 `DELETE /queue` (수강신청 종료, 로그아웃)를 호출하면 `redis/leave.lua` 가 대기열, 입장 목록, `seen:<학기>` 에서 한 번에 지웁니다. 여러 번 호출해도 결과가 같고, 비워진 자리는 다음 워커 주기에 다음 대기자에게 갑니다.
5. 입장권이 있는 학생의 신청, 취소는 앱 서버가 Redis 의 남은 좌석(`seats:<학기>`)과 학생별 신청 목록(`student:offerings:<학기>:<학번>`)을 Lua 스크립트로 원자적으로 바꾼 뒤 `enrollment-events` 토픽에 이벤트를 보내고 응답합니다.
6. enrollment-consumer 가 이벤트를 읽어 `enrollment` 테이블에 반영하고, DB 반영이 끝난 뒤에 오프셋을 커밋합니다. 같은 이벤트를 다시 받아도 결과가 같도록 SQL 이 작성되어 있습니다. DB 오류는 0.5초, 1초, 2초 간격으로 3번 다시 시도하고, 그래도 실패하거나 해석할 수 없는 메시지는 `enrollment-events-dlt` 로 보낸 뒤 커밋합니다.

### 프리웜

queue-worker 는 `ready:<학기>` 키가 없으면 입장 처리 전에 DB 에서 현재 학기 강의의 남은 좌석(정원 - `ENROLLED` 수)을 `HSETNX` 로, `ENROLLED` 신청 내역을 학생별 SET 에 `SADD` 로 채운 뒤 `ready:<학기>` 를 `1` 로 설정합니다. 이미 있는 좌석 값은 덮어쓰지 않으므로 여러 번 실행해도 결과가 같습니다. 프리웜이 끝나기 전의 신청은 "수강신청 준비 중입니다" 로 거절됩니다.

queue-worker 는 반드시 한 개만 실행합니다. `admit.lua` 자체는 원자적이지만 워커가 여러 개면 입장 주기가 겹쳐 로그와 입장 속도를 해석하기 어렵습니다.

### Kafka 토픽

`kafka-init` 컨테이너가 Kafka 가 healthy 가 된 뒤 한 번 실행되어 토픽을 만들고 종료합니다. 이미 있으면 건너뜁니다. 토픽 자동 생성은 꺼 두었습니다.

| 토픽 | 파티션 | 복제 | 키 | 값 |
|---|---|---|---|---|
| `enrollment-events` | 3 | 1 | 학번 | `EnrollmentEvent` protobuf |
| `enrollment-events-dlt` | 1 | 1 | 학번 | 실패한 원본 메시지 (헤더 `source-topic`, `source-partition`, `source-offset`, `error`) |

Kafka 는 리스너 두 개를 둡니다. 같은 compose 네트워크의 컨테이너는 `kafka:9092` (INTERNAL), 앱 VM 은 `192.168.64.34:9092` (EXTERNAL, 컨테이너 9094 포트) 로 접속합니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group enrollment-consumer'
```

두 번째 명령의 `LAG` 이 0 이면 모든 이벤트가 DB 에 반영된 상태입니다.

### 로그

queue-worker 와 enrollment-consumer 는 sa-infra 의 `~/sa/deploy/infra/logs/queue-worker/server.log`, `~/sa/deploy/infra/logs/enrollment-consumer/server.log` 에 기록합니다. queue-worker 는 입장, 이탈(시간 초과로 지운 대기자) 인원과 10초마다 대기열, 입장 인원 수를, enrollment-consumer 는 이벤트마다 이벤트 ID, 유형, 학번, 강의 번호, 결과(`APPLIED`, `NO_CHANGE`, `DEAD_LETTER`)를 남깁니다.

```bash
ssh sa-infra 'tail -f ~/sa/deploy/infra/logs/queue-worker/server.log'
ssh sa-infra 'tail -f ~/sa/deploy/infra/logs/enrollment-consumer/server.log'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml logs -f queue-worker enrollment-consumer'
```

### 다시 실행하기 (초기화)

컨슈머의 LAG 이 0 인지 확인한 뒤 DB 의 현재 학기 신청 내역을 지우고, Redis 의 학기 키를 지웁니다. queue-worker 는 `ready:<학기>` 가 사라진 것을 감지하면 다음 주기에 DB 기준으로 다시 프리웜합니다. 순서를 바꾸면 지우기 전의 DB 내용으로 프리웜될 수 있습니다.

```bash
ssh sa-db 'cd ~/sa && docker compose -f deploy/db/docker-compose.yml exec postgres psql -U serverarchitecture -d serverarchitecture -c "DELETE FROM enrollment e USING course_offering o WHERE e.offering_id = o.offering_id AND o.semester = '"'"'2026-2'"'"'"'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis sh -c "redis-cli --scan --pattern \"*:2026-2*\" | xargs -r redis-cli del"'
```

지워지는 키는 `queue:2026-2`, `active:2026-2`, `seats:2026-2`, `ready:2026-2`, `student:offerings:2026-2:*` 입니다. 이벤트 기록까지 지우려면 Kafka 볼륨을 지우고 다시 올립니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml rm -sf kafka kafka-init enrollment-consumer && docker volume rm sa-infra_kafka-data && docker compose -f deploy/infra/docker-compose.yml up -d'
```

## 단일 머신 실행 (All-in-one)

저장소 루트에서 실행합니다.

```bash
mkdir -p logs/server1 logs/server2 logs/queue-worker logs/enrollment-consumer
docker compose up --build
```

Linux 에서는 `sudo chown -R 10001:10001 logs` 로 로그 디렉터리 소유자를 컨테이너 사용자에 맞춥니다.

브라우저에서 http://localhost 를 엽니다. postgres, redis, kafka, kafka-init, server1, server2, queue-worker, enrollment-consumer, envoy, nginx 가 한 머신에서 실행됩니다. 컨테이너끼리는 `postgres`, `redis`, `kafka:9092` 이름으로 접속합니다. envoy 는 `deploy/allinone/envoy.yaml` (엔드포인트 `server1:8081`, `server2:8081`) 을, nginx 는 `deploy/lb/nginx/default.conf` 를 사용합니다.
