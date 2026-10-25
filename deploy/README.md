# 배포 가이드

## 구성

| VM | IP | 컨테이너 | compose 파일 |
|---|---|---|---|
| sa-lb | 192.168.64.30 | envoy | `deploy/lb/docker-compose.yml` |
| sa-db | 192.168.64.33 | data | `deploy/db/docker-compose.yml` |
| sa-infra | 192.168.64.34 | redis, redis-exporter, kafka, kafka-init, queue-worker, enrollment-consumer, prometheus, grafana | `deploy/infra/docker-compose.yml` |
| sa-infra | 192.168.64.34 | auth, academic, enrollment | `deploy/app/docker-compose.yml` |

기본 구성은 부하 실험(`experiments/RESULTS.md`)에서 통과한 최소 구성 `R1_C0.5_M512m_Q200_V3` 입니다. VM 세 대만 쓰고 앱 서비스는 sa-infra 에 하나씩 올립니다. sa-app1(192.168.64.31), sa-app2(192.168.64.32)를 쓰는 5대 구성은 아래 "5대 구성"에 있습니다.

```
콘솔 Client ──gRPC──▶ sa-lb envoy :8080 ──┬─ /serverarchitecture.AuthService/       ──▶ sa-infra auth       :9101
                                          ├─ /serverarchitecture.AcademicService/   ──▶ sa-infra academic   :9102
                                          └─ /serverarchitecture.EnrollmentService/ ──▶ sa-infra enrollment :9103
                                                                                       │ 학사 조회는 다시 envoy :8080 경유
auth, academic, enrollment, queue-worker, enrollment-consumer ──gRPC──▶ sa-db data :9200
enrollment, queue-worker ──▶ sa-infra redis :6379
enrollment ──▶ sa-infra kafka :9092 ──▶ enrollment-consumer ──▶ sa-db data
```

PostgreSQL 과 nginx 는 없습니다. 저장은 sa-db 의 Data 서버(범용 CRUD, 메모리 + 파일)가 맡고, 웹 프론트엔드와 gRPC-Web, REST 트랜스코딩은 범위에서 뺐습니다.

## 이미지

| 이미지 | Dockerfile | 시작 스크립트 |
|---|---|---|
| `sa-data` | `Data/Dockerfile` | `/app/bin/Data` |
| `sa-server` | `Server/Dockerfile` | `/app/bin/AuthServer`, `AcademicServer`, `EnrollmentServer`, `QueueWorker`, `EnrollmentConsumer` (기본 `/app/bin/Server` 는 EnrollmentServer) |
| `sa-envoy` | `deploy/envoy/Dockerfile` | envoy, 설정은 compose 에서 마운트 |
| `sa-client` | `Client/Dockerfile` | `/app/bin/Client` (선택) |

서비스 다섯 개는 모두 같은 `sa-server` 이미지를 쓰고 compose 의 `entrypoint` 로 구분합니다. 컨테이너는 UID 10001 로 실행되며 로그는 `/app/logs` 에, Data 서버의 파일은 볼륨 `/app/data` (`DATA_DIR`) 에 씁니다.

콘솔을 컨테이너로 실행할 때는 올인원 네트워크에 붙입니다.

```bash
docker build -f Client/Dockerfile -t sa-client:latest .
docker run --rm -it --network sa_default sa-client:latest
```

## 비밀 값 (.env)

`JWT_SECRET` 은 저장소에 넣지 않고 각 compose 파일 옆의 `.env` 에 둡니다. `deploy/.gitignore` 가 `deploy` 아래의 모든 `.env` 를 제외합니다. 값이 없으면 compose 가 시작하지 않습니다.

```bash
printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '\n')" > deploy/app/.env
```

5대 구성에서 sa-app1 과 sa-app2 는 같은 값을 써야 한쪽에서 발급한 토큰을 다른 쪽이 검증하고, 수강신청 서비스가 학사 서비스를 부를 때 쓰는 서비스 토큰(`Role.SERVICE`)도 같은 키로 검증됩니다. 로컬 올인원은 `deploy/allinone/.env` 를 씁니다.

## VM별 배포

| VM | vCPU | 메모리 | 디스크 |
|---|---|---|---|
| sa-lb | 1 | 952 MiB | 15 GB |
| sa-db | 1 | 952 MiB | 15 GB |
| sa-infra | 2 | 2956 MiB | 15 GB |

VM 메모리가 작아 이미지는 Mac 에서 빌드하고 tar 로 옮깁니다. 이미지와 저장소는 rsync 로 복사하며 `.env` 는 복사하지 않습니다.

```bash
docker build -f Server/Dockerfile -t sa-server:latest .
docker build -f Data/Dockerfile -t sa-data:latest .
docker build -f deploy/envoy/Dockerfile -t sa-envoy:latest .
mkdir -p /tmp/sa-images
docker save sa-server:latest -o /tmp/sa-images/sa-server.tar
docker save sa-data:latest -o /tmp/sa-images/sa-data.tar
docker save sa-envoy:latest -o /tmp/sa-images/sa-envoy.tar
rsync -av /tmp/sa-images/sa-envoy.tar sa-lb:~/images/
rsync -av /tmp/sa-images/sa-server.tar sa-infra:~/images/
rsync -av /tmp/sa-images/sa-data.tar sa-db:~/images/
for h in sa-lb sa-db sa-infra; do
  rsync -av --delete --exclude .git --exclude build --exclude .gradle --exclude node_modules --exclude dist --exclude logs --exclude .env ./ $h:~/sa/
done
```

각 VM 에서 `docker load -i ~/images/<이미지>.tar` 로 이미지를 올린 뒤 db, infra, app, lb 순서로 시작합니다. 서비스는 시작할 때 Data 서버에 시드를 넣습니다(auth 는 `users`, academic 은 `students`, `courses`, `offerings`). `SEED_LOADTEST=true` 이면 부하 시험용 계정과 강의를 더 넣으며 약 7,330 건이라 `DATA_FSYNC=always` 에서 수십 초 걸립니다. 확인은 auth, academic 이 healthy 가 된 뒤에 합니다.

```bash
sa-db:    cd ~/sa && mkdir -p deploy/db/logs && sudo chown 10001:10001 deploy/db/logs && docker compose -f deploy/db/docker-compose.yml up -d --no-build
sa-infra: cd ~/sa && mkdir -p deploy/infra/logs/queue-worker deploy/infra/logs/enrollment-consumer && sudo chown -R 10001:10001 deploy/infra/logs && docker compose -f deploy/infra/docker-compose.yml up -d --no-build
sa-infra: cd ~/sa && mkdir -p deploy/app/logs/auth deploy/app/logs/academic deploy/app/logs/enrollment && sudo chown -R 10001:10001 deploy/app/logs && docker compose -f deploy/app/docker-compose.yml up -d --no-build --wait
sa-lb:    cd ~/sa && docker compose -f deploy/lb/docker-compose.yml up -d --no-build
```

`.env` 의 `SERVER_NAME`(기본 `sa-infra`)은 컨테이너 호스트 이름(`sa-infra-auth` 등)이 되어 접근 로그의 `server=` 값으로 남습니다.

이전 구조(PostgreSQL, nginx)에서 바꿀 때는 옛 컨테이너를 `docker stop` 으로 멈추기만 하고 볼륨은 남깁니다. sa-infra Redis 에 남은 학기 키(`ready:2026-2` 등)는 지워야 queue-worker 가 새 Data 서버로 좌석을 프리웜합니다.

## 설정 변경

compose 파일과 같은 디렉터리의 `.env` 로 덮어씁니다.

| 파일 위치 | 변수 | 기본값 |
|---|---|---|
| `deploy/app/.env` | `JWT_SECRET` | 없음 (필수, 32자 이상) |
| `deploy/app/.env` | `DATA_HOST`, `DATA_PORT`, `DATA_DEADLINE_MS`, `SEED_ON_START`, `SEED_LOADTEST` | `192.168.64.33`, `9200`, `3000`, `true`, `true` (올인원은 `false`) |
| `deploy/app/.env` | `SERVER_NAME` | `sa-infra` |
| `deploy/app/.env` | `APP_CPUS`, `APP_MEM`, `APP_HEAP` | `0.5`, `512m`, `256m` (세 서비스 공통 값, 아래 서비스별 값이 우선) |
| `deploy/app/.env` | `AUTH_CPUS`, `AUTH_MEM`, `AUTH_HEAP` | 공통 값 |
| `deploy/app/.env` | `ACADEMIC_CPUS`, `ACADEMIC_MEM`, `ACADEMIC_HEAP` | 공통 값 |
| `deploy/app/.env` | `ENROLLMENT_CPUS`, `ENROLLMENT_MEM`, `ENROLLMENT_HEAP` | 공통 값 |
| `deploy/app/.env` | `ACADEMIC_HOST`, `ACADEMIC_PORT`, `ACADEMIC_DEADLINE_MS` | `192.168.64.30`, `8080`, `3000` (enrollment 가 envoy 를 거쳐 academic 호출) |
| `deploy/app/.env` | `REDIS_HOST`, `REDIS_PORT`, `KAFKA_BOOTSTRAP` | `192.168.64.34`, `6379`, `192.168.64.34:9092` |
| `deploy/app/.env` | `SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS`, `POLL_INTERVAL_MS`, `WATCH_INTERVAL_MS` | `2026-2`, `200`, `300`, `5000`, `1000` |
| `deploy/app/.env` | `REDIS_POOL_MAX`, `REDIS_POOL_MIN_IDLE`, `REDIS_POOL_MAX_WAIT_MS`, `CATALOG_REFRESH_SECONDS` | `128`, `8`, `2000`, `60` |
| `deploy/db/.env` | `DATA_FSYNC` | `always` (`batch`, `never`) |
| `deploy/db/.env` | `DATA_CPUS`, `DATA_MEM`, `DATA_HEAP` | `1`, `640m`, `400m` |
| `deploy/lb/.env` | `ENVOY_CPUS`, `ENVOY_MEM` | `1`, `512m` |
| `deploy/infra/.env` | `INFRA_HOST`, `KAFKA_CLUSTER_ID`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | `192.168.64.34`, 고정 ID, `admin`, `admin` |
| `deploy/infra/.env` | `DATA_HOST`, `DATA_PORT`, `DATA_DEADLINE_MS` | `192.168.64.33`, `9200`, `3000` |
| `deploy/infra/.env` | `SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS`, `PASS_CLAIM_SECONDS`, `WAITER_TIMEOUT_SECONDS`, `WORKER_TICK_MS` | `2026-2`, `200`, `300`, `30`, `30`, `500` |
| `deploy/infra/.env` | `REDIS_POOL_MAX`, `REDIS_POOL_MIN_IDLE`, `REDIS_POOL_MAX_WAIT_MS` | `8`, `1`, `2000` |
| `deploy/infra/.env` | `WORKER_CPUS`, `WORKER_MEM`, `WORKER_HEAP` | `1`, `384m`, `192m` (queue-worker, enrollment-consumer 각각) |
| `deploy/infra/.env` | `REDIS_CPUS`, `REDIS_MEM`, `KAFKA_CPUS`, `KAFKA_MEM`, `KAFKA_HEAP` | `1`, `256m`, `1`, `1g`, `512m` |

`SEMESTER`, `QUEUE_MAX_ACTIVE`, `PASS_TTL_SECONDS` 는 `deploy/app/.env` 와 `deploy/infra/.env` 에 같은 값을 지정합니다. `*_CPUS` 는 VM 의 vCPU 수를 넘을 수 없고, `*_HEAP` 은 `-Xmx` 로 들어가며 `*_MEM` 보다 충분히 작게(절반 정도) 둡니다.

동시 입장 인원은 Redis 의 `max:<학기>` 키로, 수강신청 시작 시각은 `open:<학기>` 키(epoch 밀리초)로 바꿀 수 있습니다. 둘 다 재시작 없이 반영됩니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis redis-cli SET max:2026-2 100'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis redis-cli SET open:2026-2 1792040400000'
```

다음 값은 파일에 직접 적혀 있습니다.

| 파일 | 내용 |
|---|---|
| `deploy/lb/envoy.yaml` | `auth`, `academic`, `enrollment` 클러스터 엔드포인트 `192.168.64.34` (STATIC, 포트 9101, 9102, 9103) |
| `deploy/infra/prometheus/prometheus.yml` | Prometheus 수집 대상 IP |

앱 서비스를 올린 VM 의 IP 가 바뀌면 `deploy/lb/envoy.yaml` 을 고친 뒤 `docker compose -f deploy/lb/docker-compose.yml restart envoy` 를 실행합니다. 설정 검사는 다음과 같습니다.

```bash
docker run --rm -v $PWD/deploy/lb/envoy.yaml:/e.yaml:ro envoyproxy/envoy:v1.40.0 --mode validate -c /e.yaml
```

## Envoy 라우팅

| 순서 | 경로 | 클러스터 | 제한 |
|---|---|---|---|
| 1 | `/serverarchitecture.EnrollmentService/WatchQueue` | enrollment | `timeout: 0s`, 라우트 `idle_timeout: 300s` (서버 스트리밍이 끊기지 않게) |
| 2 | `/serverarchitecture.AuthService/` | auth | 30초, 연결 실패와 refused-stream 재시도 2회 |
| 3 | `/serverarchitecture.AcademicService/` | academic | 5초, 재시도는 위와 같음 |
| 4 | `/serverarchitecture.EnrollmentService/` | enrollment | 위와 같음 |

세 클러스터 모두 HTTP/2 upstream, ROUND_ROBIN, outlier detection(연속 5xx 5회, 게이트웨이 실패 3회, 30초 제외, 최대 50%)입니다. auth 클러스터는 엔드포인트마다 연결 1개, HTTP/2 동시 스트림 16개로 제한하여 로그인이 몰려도 auth 에 16건씩만 들어가고 나머지는 Envoy 에서 기다립니다. `grpc_stats` 필터가 메서드별 요청, 성공, 실패 수를 남깁니다. Data 서버는 envoy 에 노출하지 않습니다.

## 로그인과 권한

로그인을 제외한 모든 RPC 는 `authorization: Bearer <JWT>` 메타데이터가 있어야 합니다. 토큰은 HS256 으로 서명하며 30분 동안 유효합니다.

| 계정 | 아이디 | 초기 비밀번호 | 역할 |
|---|---|---|---|
| 학생 18명 | 학번 (예: `60220435`, `20100124`) | 학번과 같음 | STUDENT |
| 교수 Park, Kim, Ko, Ahn, Lee | `P001` ~ `P005` | 아이디와 같음 | PROFESSOR |

비밀번호는 PBKDF2(HMAC-SHA256, 100000회)로 해시하여 Data 서버 `users` 컬렉션의 `password_hash` 에 저장합니다. 권한표는 각 서비스의 `PERMISSIONS` 에 있으며, 토큰이 없거나 위조되면 `UNAUTHENTICATED`, 역할이 맞지 않으면 `PERMISSION_DENIED` 입니다.

grpcurl 로 호출할 때는 proto 파일을 넘깁니다(서버 리플렉션은 켜지 않았습니다).

```bash
P='-plaintext -import-path Proto/src/main/proto'
TOKEN=$(grpcurl $P -proto auth.proto -d '{"user_id":"P001","password":"P001"}' 192.168.64.30:8080 serverarchitecture.AuthService/Login | jq -r .token)
grpcurl $P -proto academic.proto -H "authorization: Bearer $TOKEN" 192.168.64.30:8080 serverarchitecture.AcademicService/ListCourses
```

## 로그

| 위치 | 프로세스 |
|---|---|
| sa-infra `~/sa/deploy/app/logs/{auth,academic,enrollment}` | 서비스 세 개 |
| sa-db `~/sa/deploy/db/logs` | Data 서버 |
| sa-infra `~/sa/deploy/infra/logs/{queue-worker,enrollment-consumer}` | 워커, 컨슈머 |

각 디렉터리에 `access.log`(RPC 한 번에 한 줄)와 `server.log` 가 있고 자정(Asia/Seoul)마다 날짜를 붙여 넘깁니다. 비밀번호와 토큰은 기록하지 않습니다.

```
2026-10-15 01:52:03.118 | server=sa-infra-auth | user=20100123   | command=LOGIN          | result=OK                |    85ms
```

## 포트

| VM | 포트 | 용도 |
|---|---|---|
| sa-lb | 8080 | envoy gRPC |
| sa-lb | 9901 | envoy admin, `/stats/prometheus` |
| sa-infra | 9101, 9102, 9103 | auth, academic, enrollment (envoy 가 호출) |
| sa-db | 9200 | Data 서버 |
| sa-infra | 6379 | Redis |
| sa-infra | 9121 | redis_exporter |
| sa-infra | 9092 | Kafka (EXTERNAL 리스너, 컨테이너 내부 9094) |
| sa-infra | 9090 | Prometheus |
| sa-infra | 3000 | Grafana |

## 5대 구성

앱 서비스를 sa-app1(192.168.64.31), sa-app2(192.168.64.32)에 두 벌 올리는 구성입니다. 부하 실험의 기준 구성 `R2_C2.0_M512m_Q200_V5` 가 이것입니다.

| VM | vCPU | 메모리 | 디스크 |
|---|---|---|---|
| sa-app1, sa-app2 | 2 | 1955 MiB | 15 GB |

1. sa-infra 에서 `docker compose -f deploy/app/docker-compose.yml stop` 으로 앱 서비스를 멈춥니다.
2. 이미지와 저장소를 sa-app1, sa-app2 에도 rsync 로 복사하고 `docker load` 합니다.
3. sa-app1 과 sa-app2 의 `deploy/app/.env` 에 같은 `JWT_SECRET` 과 각자의 `SERVER_NAME`(`sa-app1`, `sa-app2`)을 적고, 기준 구성이면 `APP_CPUS=2` 를 더합니다. sa-infra 의 비밀 값과 다르면 sa-infra 의 앱 서비스와 함께 켜면 안 됩니다.
4. 두 VM 에서 위 "VM별 배포"의 앱 명령을 실행합니다.
5. sa-lb 에서 `git show 8be0246:deploy/lb/envoy.yaml > deploy/lb/envoy.yaml` 로 두 앱 VM 을 가리키는 설정을 꺼내 검사한 뒤 `docker compose -f deploy/lb/docker-compose.yml restart envoy` 를 실행합니다.

envoy 는 서비스마다 두 앱 VM 에 라운드로빈으로 분산하고, 2초마다 TCP 헬스체크를 하여 두 번 실패한 엔드포인트를 뺍니다. sa-app2 를 멈추면 sa-app1 만으로 처리합니다.

```bash
sa-app2: cd ~/sa && docker compose -f deploy/app/docker-compose.yml stop
sa-app2: cd ~/sa && docker compose -f deploy/app/docker-compose.yml start
```

서비스 하나만 줄일 때는 `stop enrollment` 처럼 서비스 이름을 붙입니다. 엔드포인트 상태는 `http://192.168.64.30:9901/clusters` 에서, 요청 수는 Grafana 에서 확인합니다.

## 실험 설정 전환 체크리스트

각 실행 전에 다음을 차례로 확인합니다.

1. 바꿀 값을 해당 VM 의 `.env` 에 적습니다(`deploy/app/.env`, `deploy/db/.env`, `deploy/infra/.env`, `deploy/lb/.env`). 5대 구성이면 앱 값은 sa-app1, sa-app2 에 똑같이 적습니다.
2. `QUEUE_MAX_ACTIVE` 를 바꾸면 `deploy/app/.env` 와 `deploy/infra/.env` 에 같은 값을 적고, 실행 중에는 Redis `max:2026-2` 키로도 바꿀 수 있습니다.
3. 값을 적은 VM 에서 `docker compose -f deploy/<vm>/docker-compose.yml up -d --no-build --wait` 로 바뀐 컨테이너만 다시 만듭니다.
4. `docker inspect -f '{{.Name}} {{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' $(docker ps -q)` 와 `docker compose ... exec <서비스> printenv JAVA_OPTS` 로 반영을 확인합니다.
5. 5대 구성에서 복제본 수는 sa-app2 의 `stop`, `start` 로 바꾸고 `http://192.168.64.30:9901/clusters` 에서 `health_flags` 를 확인합니다.
6. 아래 "실행 사이 초기화"로 부하 학생의 상태를 지웁니다.
7. 로그인과 과목 조회로 동작을 확인한 뒤 부하를 겁니다. 설정 값과 시작 시각을 실험 기록에 남깁니다.

## 실행 사이 초기화

부하 학생(30000001 ~ 30003660)의 상태만 지웁니다. 실제 계정(60220435, P001 ~ P005, 다른 시드 학생)과 그 신청, 볼륨은 그대로 둡니다. sa-infra 창에서 실행합니다.

```bash
sa-infra: cd ~/sa && experiments/reset-load.sh
```

1. enrollment-consumer LAG 이 0 이 아니면 멈춥니다.
2. `grafana/k6` 컨테이너가 Data gRPC 로 `enrollments` 에서 부하 학생의 행을 모두 지웁니다(`experiments/k6/reset-load.js`).
3. Redis `queue`, `active`, `seen` 에서 부하 학생만 빼고, 부하 학생의 `student:offerings`, `lock:student` 키를 지웁니다.
4. `seats:2026-2` 의 개설 강의 101 ~ 110 을 정원에서 부하 학생이 아닌 신청 수를 뺀 값(현재 300)으로 되돌립니다.
5. 남은 부하 학생 행(`REMAINING=0`)과 Redis 상태를 출력합니다.

부하는 Mac 에서 겁니다(`experiments/MATRIX.md`).

```bash
TARGET=192.168.64.30:8080 DATA_ADDR=192.168.64.33:9200 \
CFG_REPLICAS=1 CFG_CPU=0.5 CFG_MEM=512m CFG_QUEUE_MAX_ACTIVE=200 CFG_VMS=3 \
experiments/run.sh R1_C0.5_M512m_Q200_V3
```

## 수강신청 대기열과 이벤트 처리

| 프로세스 | 실행 위치 | 시작 스크립트 | 개수 |
|---|---|---|---|
| 수강신청 서비스 | sa-infra `enrollment` | `/app/bin/EnrollmentServer` | 1 |
| 대기열 워커 | sa-infra `queue-worker` | `/app/bin/QueueWorker` | 반드시 1 |
| 이벤트 컨슈머 | sa-infra `enrollment-consumer` | `/app/bin/EnrollmentConsumer` | 1 |

1. 학생이 `EnterQueue` 로 대기열(`queue:<학기>`)에 들어가고 `WatchQueue` 서버 스트림으로 순번을 받습니다. 스트림이 열려 있는 동안이 하트비트입니다.
2. queue-worker 가 `WORKER_TICK_MS` 마다 `admit.lua` 로 만료된 입장권과 응답 없는 대기자를 지우고 빈 자리만큼 입장시킵니다. 입장하면 스트림이 `ADMITTED` 를 보내고 끝납니다.
3. 신청, 취소는 수강신청 서비스가 Redis 좌석과 학생별 신청 목록을 Lua 로 원자적으로 바꾼 뒤 `enrollment-events` 에 이벤트를 보내고 응답합니다.
4. enrollment-consumer 가 이벤트를 Data 서버 `enrollments` 컬렉션(`학번:개설강의id`)에 반영한 뒤 오프셋을 커밋합니다. Data 장애는 다시 시도하고, 해석할 수 없는 메시지는 `enrollment-events-dlt` 로 보냅니다.

queue-worker 는 `ready:<학기>` 키가 없으면 Data 서버의 `offerings` 와 `enrollments` 로 Redis 좌석과 신청 목록을 프리웜합니다.

```bash
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group enrollment-consumer'
```

`LAG` 이 0 이면 모든 이벤트가 Data 서버에 반영된 상태입니다.

### 다시 실행하기 (초기화)

컨슈머 LAG 이 0 인지 확인한 뒤 Data 볼륨을 지우고 Redis 학기 키를 지운 다음 앱 서비스를 다시 시작합니다. 시드는 서비스가 시작할 때 다시 들어갑니다.

```bash
ssh sa-db    'cd ~/sa && docker compose -f deploy/db/docker-compose.yml down -v && docker compose -f deploy/db/docker-compose.yml up -d'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml exec redis sh -c "redis-cli --scan --pattern \"*:2026-2*\" | xargs -r redis-cli del"'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/app/docker-compose.yml restart'
```

## 단일 머신 실행 (All-in-one)

`deploy/allinone/docker-compose.yml` 이 한 머신에 전체를 올립니다. 저장소 루트의 `docker-compose.yml` 은 이 파일을 `include` 하므로 루트에서 `docker compose up` 해도 같습니다.

| 단계 | 컨테이너 |
|---|---|
| 1 | data, redis, kafka → kafka-init, envoy |
| 2 | auth1, auth2, academic1, academic2 (data healthy 후) |
| 3 | enrollment1, enrollment2 (academic, envoy, redis healthy, kafka-init 완료 후) |
| 4 | queue-worker, enrollment-consumer (enrollment healthy 후) |
| 선택 | redis-exporter, prometheus :9090, grafana :3000 (`--profile monitoring`) |

enrollment 는 `ACADEMIC_HOST=envoy`, `ACADEMIC_PORT=8080` 으로 envoy 를 거쳐 academic 두 복제본에 분산합니다. envoy 는 `deploy/allinone/envoy.yaml`(엔드포인트 `auth1`, `auth2` 등)을 씁니다. Data 서버는 디버깅용으로 `127.0.0.1:9200` 에만 열려 있습니다.

루트에서 실행하면 루트의 `.env` 를 읽고, 없으면 `.env.example` 의 로컬 전용 값을 씁니다. 호스트 포트는 `ENVOY_PORT`, `DATA_HOST_PORT` 등으로 바꿀 수 있습니다.

```bash
docker compose up -d --build --wait
docker compose run --rm client
```

로그는 `deploy/allinone/logs/<컨테이너>` 에 남습니다.

### 로컬 E2E

```bash
deploy/allinone/e2e-local.sh
```

스택을 볼륨째 지우고 새로 빌드해 올린 뒤 envoy(`localhost:8080`)로 다음을 확인합니다. grpcurl 이 없으면 `fullstorydev/grpcurl` 컨테이너를 compose 네트워크에서 실행합니다.

1. 60220435, P001 로그인과 잘못된 비밀번호 거절, 과목 7개, 학생 18명 조회
2. 대기열 진입과 `WatchQueue` 스트림으로 입장, 개설 강의 8개 조회
3. 신청 성공(8), 중복 신청 ALREADY_EXISTS(8), 선수과목 ABORTED(9), 신청 후 취소(15), 시간 충돌 ABORTED(20130094 가 9 신청 후 10), 입장권 없는 학생의 신청 FAILED_PRECONDITION
4. 같은 학생(20130095)이 시간이 겹치는 9, 10을 동시에 신청해 정확히 하나만 성공
5. 학생 17명이 정원 5명인 개설 강의 12에 동시에 신청해 정확히 5명 성공, 나머지는 모두 RESOURCE_EXHAUSTED, 남은 자리 0
6. 컨슈머 반영 후 Data 서버 `enrollments` 의 ENROLLED 항목이 성공한 신청과 정확히 같고, 취소한 항목은 CANCELLED
7. Data 컨테이너를 재시작해도 컬렉션별 개수와 ENROLLED 목록이 같고 로그인이 됨
8. auth1, academic1, enrollment1 을 멈춘 상태에서 envoy 경유 호출이 모두 성공(검사 뒤 다시 올림)

실패한 항목이 있으면 종료 코드 1 입니다. 스택은 끝난 뒤에도 켜 둡니다.
