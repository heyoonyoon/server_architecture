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
  rsync -av --delete --exclude .git --exclude build --exclude .gradle --exclude node_modules --exclude dist ./ $h:~/sa/
done
```

```bash
ssh sa-db    'cd ~/sa && docker compose -f deploy/db/docker-compose.yml up -d'
ssh sa-app1  'cd ~/sa && docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-app2  'cd ~/sa && docker compose -f deploy/app/docker-compose.yml up -d --build'
ssh sa-lb    'cd ~/sa && docker compose -f deploy/lb/docker-compose.yml up -d --build'
ssh sa-infra 'cd ~/sa && docker compose -f deploy/infra/docker-compose.yml up -d'
```

sa-app1 과 sa-app2 는 같은 compose 파일을 그대로 사용합니다.

`db/init` 스크립트는 postgres 볼륨이 비어 있을 때 한 번만 실행됩니다. DB 이름이나 계정을 바꾼 뒤에는 `docker compose -f deploy/db/docker-compose.yml down -v` 로 볼륨을 지우고 다시 올립니다.

## 설정 변경

IP나 계정을 바꾸려면 각 compose 파일과 같은 디렉터리에 `.env` 파일을 두고 값을 덮어씁니다.

| 파일 위치 | 변수 | 기본값 |
|---|---|---|
| `deploy/app/.env` | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `192.168.64.33`, `5432`, `serverarchitecture`, `serverarchitecture`, `serverarchitecture` |
| `deploy/db/.env` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `serverarchitecture` |
| `deploy/infra/.env` | `INFRA_HOST`, `KAFKA_CLUSTER_ID`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD` | `192.168.64.34`, 고정 ID, `admin`, `admin` |

다음 값은 환경 변수가 아니라 파일에 직접 적혀 있습니다.

| 파일 | 내용 |
|---|---|
| `deploy/lb/envoy.yaml` | `grpc_servers` 클러스터 엔드포인트 `192.168.64.31:8081`, `192.168.64.32:8081` (STATIC), CORS 허용 Origin |
| `deploy/infra/prometheus/prometheus.yml` | Prometheus 수집 대상 IP |

Envoy 는 설정 파일 안에서 환경 변수를 치환하지 않으므로 앱 VM IP가 바뀌면 `deploy/lb/envoy.yaml` 을 수정한 뒤 `docker compose -f deploy/lb/docker-compose.yml restart envoy` 를 실행합니다.

## 포트

| VM | 포트 | 용도 |
|---|---|---|
| sa-lb | 80 | nginx (정적 프론트엔드, `/api` → envoy) |
| sa-lb | 8080 | envoy (gRPC-Web, 네이티브 gRPC) |
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

k6 와 Java 콘솔 클라이언트는 `192.168.64.30:8080` 으로 네이티브 gRPC 를 호출합니다.

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
docker compose up --build
```

브라우저에서 http://localhost 를 엽니다. postgres, server1, server2, envoy, nginx 가 한 머신에서 실행됩니다. envoy 는 `deploy/allinone/envoy.yaml` (엔드포인트 `server1:8081`, `server2:8081`) 을, nginx 는 `deploy/lb/nginx/default.conf` 를 사용합니다.
