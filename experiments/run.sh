#!/usr/bin/env bash
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
name="${1:-}"
root="${2:-$here/results}"
if [ -z "$name" ]; then
    echo "사용법: experiments/run.sh <구성 이름> [결과 루트]"
    echo "  예: TARGET=192.168.64.30:8080 DATA_ADDR=192.168.64.33:9200 CFG_REPLICAS=2 CFG_CPU=2.0 CFG_MEM=1g CFG_QUEUE_MAX_ACTIVE=100 CFG_VMS=5 experiments/run.sh R2_C2.0_M1g_Q100_V5"
    exit 1
fi

export TARGET="${TARGET:-localhost:8080}"
export DATA_ADDR="${DATA_ADDR:-localhost:9200}"
export STUDENTS="${STUDENTS:-3660}"
export ARRIVAL_SPREAD_S="${ARRIVAL_SPREAD_S:-2}"
export PROTO_DIR="${PROTO_DIR:-$here/../Proto/src/main/proto}"
export CONFIG="$name"
base_error_pct="${BASE_ERROR_PCT:-4.00}"
p95_limit_ms="${P95_LIMIT_MS:-2000}"

stamp="$(date +%Y%m%d-%H%M%S)"
dir="$root/${name}_$stamp"
mkdir -p "$dir"
export SUMMARY_DIR="$dir"

{
    echo "CONFIG=$name"
    echo "STARTED_AT=$(date '+%Y-%m-%dT%H:%M:%S%z')"
    echo "TARGET=$TARGET"
    echo "DATA_ADDR=$DATA_ADDR"
    echo "STUDENTS=$STUDENTS"
    echo "ARRIVAL_SPREAD_S=$ARRIVAL_SPREAD_S"
    echo "K6_VERSION=$(k6 version 2>/dev/null | head -1)"
    env | grep '^CFG_' | sort
} > "$dir/config.env"

echo "결과 디렉터리: $dir"
k6 run -q "$here/k6/verify-seats.js" -e PHASE=pre > "$dir/verify-pre.log" 2>&1
baseline="$(sed -n 's/^V_DATA_LOAD=//p' "$dir/verify-pre.env" 2>/dev/null)"
baseline="${baseline:-0}"
if [ "$baseline" != "0" ]; then
    echo "주의: 부하 학생의 신청이 이미 ${baseline}건 있습니다. 초기화 없이 진행하면 결과가 섞입니다."
    [ "${REQUIRE_CLEAN:-1}" = "1" ] && { echo "REQUIRE_CLEAN=0 으로 무시할 수 있습니다."; exit 2; }
fi

started_ms="$(python3 -c 'import time; print(int(time.time()*1000))')"
k6 run "$here/k6/enroll-open.js" 2>&1 | tee "$dir/k6.log"
k6_exit="${PIPESTATUS[0]}"
ended_ms="$(python3 -c 'import time; print(int(time.time()*1000))')"
echo "K6_EXIT=$k6_exit" >> "$dir/config.env"
echo "K6_WALL_S=$(( (ended_ms - started_ms) / 1000 ))" >> "$dir/config.env"

if [ ! -f "$dir/result.env" ]; then
    echo "k6 요약이 없습니다. $dir/k6.log 를 확인하세요."
    exit 1
fi
source "$dir/result.env"

k6 run -q "$here/k6/verify-seats.js" -e PHASE=post -e EXPECTED="$K6_SUCCESS" -e BASELINE="$baseline" 2>&1 | tee "$dir/verify-post.log"
source "$dir/verify-post.env"

verdict=PASS
reasons=""
fail() { verdict=FAIL; reasons="$reasons${reasons:+, }$1"; }
awk -v a="$K6_ENROLL_ERROR_PCT" -v b="$base_error_pct" 'BEGIN { exit !(a < b) }' || fail "수강신청 오류율 ${K6_ENROLL_ERROR_PCT}% >= ${base_error_pct}%"
awk -v a="$K6_RPC_ERROR_PCT" -v b="$base_error_pct" 'BEGIN { exit !(a < b) }' || fail "전체 RPC 오류율 ${K6_RPC_ERROR_PCT}% >= ${base_error_pct}%"
[ "$K6_ENROLL_P95" -le "$p95_limit_ms" ] || fail "수강신청 p95 ${K6_ENROLL_P95}ms > ${p95_limit_ms}ms"
[ "$V_OVER_CAPACITY" = "0" ] || fail "정원 초과 ${V_OVER_CAPACITY}개"
[ "$V_LOAD_SECTION_ENROLLED" -le "$V_LOAD_SECTION_SEATS" ] || fail "부하 분반 신청 ${V_LOAD_SECTION_ENROLLED} > 좌석 ${V_LOAD_SECTION_SEATS}"
[ "$V_DATA_LOAD" = "$(( K6_SUCCESS + baseline ))" ] || fail "Data ${V_DATA_LOAD}건 != k6 성공 ${K6_SUCCESS}건 + 기준 ${baseline}건"
[ "$V_REDIS_MISMATCH" = "0" ] || fail "Redis 불일치 ${V_REDIS_MISMATCH}"
[ "$K6_NEGATIVE_REMAINING" = "0" ] || fail "남은 좌석 음수 응답 ${K6_NEGATIVE_REMAINING}건"

{
    echo "VERDICT=$verdict"
    echo "REASONS=\"$reasons\""
} >> "$dir/config.env"

index="$root/index.tsv"
[ -f "$index" ] || printf 'config\tstarted\tverdict\tstudents\tadmitted\tfailed\tattempts\tsuccess\tfull\tenroll_err_pct\trpc_err_pct\tenroll_p95_ms\tenroll_p99_ms\tlast_admit_ms\tdata_load\tover_capacity\tredis_mismatch\tdrain_ms\tdir\n' > "$index"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$name" "$stamp" "$verdict" "$K6_STUDENTS" "$K6_ADMITTED" "$K6_FAILED" "$K6_ATTEMPTS" "$K6_SUCCESS" "$K6_FULL" \
    "$K6_ENROLL_ERROR_PCT" "$K6_RPC_ERROR_PCT" "$K6_ENROLL_P95" "$K6_ENROLL_P99" "$K6_LAST_ADMIT_MS" \
    "$V_DATA_LOAD" "$V_OVER_CAPACITY" "$V_REDIS_MISMATCH" "$V_DRAIN_MS" "$dir" >> "$index"

echo "판정: $verdict${reasons:+ ($reasons)}"
echo "기록: $index"
[ "$verdict" = PASS ]
