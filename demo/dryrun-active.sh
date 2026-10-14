#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh

count="${1:-700}"
first_id="$LT_FIRST"
run_dir="results/runs/dryrun-$count-$(date +%m%d-%H%M%S)"
mkdir -p "$run_dir"

echo "================================================================"
echo "  리허설: 입장권을 가진 학생 ${count}명이 동시에 신청할 때 서버가 버티는지"
echo "================================================================"

if [ "$count" -gt $(( LT_LAST - LT_FIRST + 1 )) ]; then
    echo "최대 $(( LT_LAST - LT_FIRST + 1 ))명까지 가능합니다" >&2
    exit 1
fi
guard_real_users

echo "[1/4] 초기화"
./reset.sh | sed 's/^/      /' || exit 1

if [ ! -f .tokens.json ]; then
    node make-tokens.mjs | sed 's/^/      /' || exit 1
fi

echo "[2/4] 동시 입장 한도를 ${count}명으로 정하고, 학생 ${count}명에게 입장권을 미리 줍니다 (대기열 통과한 상태)"
redis_cmd SET "max:$SEMESTER" "$count" > /dev/null
redis_cmd EVAL "for i = 0, tonumber(ARGV[2]) - 1 do redis.call('ZADD', KEYS[1], tonumber(ARGV[3]), tostring(tonumber(ARGV[1]) + i)) end
return redis.call('ZCARD', KEYS[1])" 1 "active:$SEMESTER" "$first_id" "$count" "$(( $(now_ms) + 600000 ))" | sed 's/^/      입장권 /'

echo "[3/4] 동시에 신청 시작"
k6 run --quiet --log-format raw \
    -e PREADMITTED=1 \
    -e STUDENTS="$count" \
    -e COUNTDOWN_S=5 \
    -e SUMMARY_DIR="$run_dir" \
    -e LAST_RUN_FILE="$run_dir/run.env" \
    -e MODE="dryrun-$count" \
    loadtest-0811.js 2>&1 | grep --line-buffered -v -E 'Request Failed|have been crossed|^OPEN_AT_MS=|█|초 뒤에 수강신청|지금 누르세요|^#'
k6_status="${PIPESTATUS[0]}"

echo "[4/4] 정합성과 서버 부하"
./verify-0811.sh "$run_dir/run.env" | tee "$run_dir/verify.txt"
source "$run_dir/run.env"
./prom-peaks.sh $(( K6_START_AT_MS / 1000 - 10 )) "$(date +%s)" | tee "$run_dir/prometheus.txt"

./reset.sh > /dev/null && echo "리허설 데이터를 지웠습니다 (실제 학생 데이터는 그대로, 동시 입장 한도는 기본값으로)"
echo "결과 파일: demo/$run_dir"
exit "$k6_status"
