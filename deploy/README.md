# 배포 가이드

## 구성

| VM | IP | 컨테이너 | compose 파일 |
|---|---|---|---|
| sa-lb | 192.168.64.30 | nginx, nginx-exporter, envoy | `deploy/lb/docker-compose.yml` |
| sa-app1 | 192.168.64.31 | server | `deploy/app/docker-compose.yml` |
| sa-app2 | 192.168.64.32 | server | `deploy/app/docker-compose.yml` |
| sa-db | 192.168.64.33 | postgres, postgres-exporter | `deploy/db/docker-compose.yml` |
| sa-infra | 192.168.64.34 | redis, redis-exporter, kafka, prometheus, grafana | `deploy/infra/docker-compose.yml` |

```
브라우저 ──▶ sa-lb nginx :80 ──/api──▶ sa-lb envoy :8080 ──┬──▶ sa-app1 server :8081 ──┐
                                                            └──▶ sa-app2 server :8081 ──┴──▶ sa-db postgres :5432
```

## VM별 배포

각 VM의 `~/sa` 에 저장소 전체를 복사한 뒤 해당 역할의 compose 파일을 실행합니다. 순서는 db → app1, app2 → lb → infra 를 권장합니다.

```bash
for h in sa-lb sa-app1 sa-app2 sa-db sa-infra; do
  rsync -av --delete --exclude .git --exclude build --exclude .gradle --exclude node_modules --exclude dist --exclude logs ./ $h:~/sa/
done
```

```bash
ssh sa-db    'cd ~/sa && docker compose -f deploy/db/docker-compose.yml up -d'
ssh sa-app1  'cd ~/sa && mkdir -p deploy/app/logs && sudo chown 10001:10001 deploy/app/logs && SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-app2  'cd ~/sa && mkdir -p deploy/app/logs && sudo chown 10001:10001 deploy/app/logs && SERVER_NAME=$(hostname) docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-lb    'cd ~/sa && docker compose -f deploy/lb/docker-compose.yml up -d --build'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml up -d'
```

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
| `deploy/db/.env` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `serverarchitecture` |
| `deploy/infra/.env` | `INFRA_HOST`, `KAFKA_CLUSTER_ID`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | `192.168.64.34`, 고정 ID, `admin`, `admin` |

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

새로 추가한 학생도 학번이 초기 비밀번호입니다. 비밀번호는 bcrypt (cost 12) 로 해시하여 알고리즘·cost·salt 가 포함된 하나의 문자열로 `users.password_hash` 에 저장합니다.

| 역할 | 호출 가능한 RPC |
|---|---|
| PROFESSOR | 학생 목록, 과목 목록, 학생 추가·삭제, 과목 추가·삭제 |
| STUDENT | 과목 목록 |

토큰이 없거나 만료·위조되면 `UNAUTHENTICATED`, 역할이 맞지 않으면 `PERMISSION_DENIED` 를 반환합니다.

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
| sa-infra | 9092 | Kafka |
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

## 단일 머신 실행 (All-in-one)

저장소 루트에서 실행합니다.

```bash
mkdir -p logs/server1 logs/server2
docker compose up --build
```

Linux 에서는 `sudo chown -R 10001:10001 logs` 로 로그 디렉터리 소유자를 컨테이너 사용자에 맞춥니다.

브라우저에서 http://localhost 를 엽니다. postgres, server1, server2, envoy, nginx 가 한 머신에서 실행됩니다. envoy 는 `deploy/allinone/envoy.yaml` (엔드포인트 `server1:8081`, `server2:8081`) 을, nginx 는 `deploy/lb/nginx/default.conf` 를 사용합니다.
