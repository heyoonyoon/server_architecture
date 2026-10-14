#!/usr/bin/env bash
set -euo pipefail

PROM="${PROM_URL:-http://192.168.64.34:9090}"
start="${1:?사용법: demo/prom-peaks.sh <시작 epoch초> <끝 epoch초>}"
end="${2:?사용법: demo/prom-peaks.sh <시작 epoch초> <끝 epoch초>}"

peak() {
    local label="$1" unit="$2" query="$3"
    local value
    value="$(curl -s "$PROM/api/v1/query_range" \
        --data-urlencode "query=$query" \
        --data-urlencode "start=$start" \
        --data-urlencode "end=$end" \
        --data-urlencode "step=5" |
        jq -r '[.data.result[]?.values[]?[1] | tonumber | select(. == . and . < 1e300)] | if length == 0 then "없음" else (max * 10 | round / 10 | tostring) end')"
    printf '  %-28s %s %s\n' "$label" "$value" "$unit"
}

echo "Prometheus 최고값 ($(date -r "$start" +%T 2>/dev/null || date -d "@$start" +%T) ~ $(date -r "$end" +%T 2>/dev/null || date -d "@$end" +%T))"
peak "gRPC 서버 요청 처리량" "req/s" 'sum(rate(envoy_cluster_upstream_rq_total{envoy_cluster_name="grpc_servers"}[15s]))'
peak "gRPC 서버 응답 p99" "ms" 'histogram_quantile(0.99, sum(rate(envoy_cluster_upstream_rq_time_bucket{envoy_cluster_name="grpc_servers"}[15s])) by (le))'
peak "Envoy 전체 응답 p99" "ms" 'histogram_quantile(0.99, sum(rate(envoy_http_downstream_rq_time_bucket[15s])) by (le))'
peak "gRPC 서버 5xx" "req/s" 'sum(rate(envoy_cluster_upstream_rq_xx{envoy_cluster_name="grpc_servers",envoy_response_code_class="5"}[15s]))'
peak "nginx 동시 연결" "개" 'sum(nginx_connections_active)'
peak "PostgreSQL 연결" "개" 'sum(pg_stat_activity_count)'
peak "PostgreSQL 트랜잭션" "tx/s" 'sum(rate(pg_stat_database_xact_commit{datname="serverarchitecture"}[15s]))'
peak "Redis 명령" "cmd/s" 'sum(rate(redis_commands_processed_total[15s]))'
