#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh

mode="${1:-}"
case "$mode" in
    A) target=10000; label="A: 대기열 없음 (동시 입장 10000명)" ;;
    B) target=50; label="B: 대기열 있음 (동시 입장 50명)" ;;
    *)
        echo "사용법: demo/live.sh A   (대기열 없이 3,660명이 한꺼번에)"
        echo "        demo/live.sh B   (대기열로 50명씩 입장)"
        exit 1
        ;;
esac
other="$([ "$mode" = A ] && echo B || echo A)"

TOKENS_FILE="${TOKENS_FILE:-.tokens.json}"
COUNTDOWN_S="${COUNTDOWN_S:-30}"
MAX_DURATION="${MAX_DURATION:-20m}"
MIN_TOKEN_MINUTES=25
run_dir="results/runs/$mode-$(date +%m%d-%H%M%S)"
mkdir -p "$run_dir"

worker_setting() {
    ssh sa-infra "docker compose -f ~/sa/deploy/infra/docker-compose.yml exec -T queue-worker printenv QUEUE_MAX_ACTIVE" 2>/dev/null | tr -d '\r'
}

echo "================================================================"
echo "  8/11 수강신청 재현  $label"
echo "================================================================"

echo "[1/5] 대기열 설정"
current="$(worker_setting)"
if [ -z "$current" ]; then
    echo "      sa-infra의 queue-worker에 접속하지 못했습니다. VM이 켜져 있는지 확인하세요." >&2
    exit 1
fi
if [ "$current" = "$target" ]; then
    echo "      이미 QUEUE_MAX_ACTIVE=$target 입니다"
else
    echo "      QUEUE_MAX_ACTIVE $current -> $target 로 바꾸고 queue-worker만 다시 띄웁니다 (앱 서버는 이 값을 쓰지 않음)"
    ssh sa-infra "cd ~/sa && QUEUE_MAX_ACTIVE=$target docker compose -f deploy/infra/docker-compose.yml up -d --no-deps queue-worker" 2>&1 | sed 's/^/      /'
    for _ in $(seq 1 30); do
        [ "$(worker_setting)" = "$target" ] && break
        sleep 1
    done
    if [ "$(worker_setting)" != "$target" ]; then
        echo "      queue-worker 설정이 바뀌지 않았습니다" >&2
        exit 1
    fi
    echo "      queue-worker QUEUE_MAX_ACTIVE=$target 확인"
fi

echo "[2/5] 초기화"
./reset.sh | sed 's/^/      /' || exit 1

echo "[3/5] 부하 테스트 학생 토큰 확인"
minutes_left=-1
if [ -f "$TOKENS_FILE" ]; then
    minutes_left="$(node -e "const t = JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')); console.log(Math.floor((t.expiresAt - Date.now()) / 60000))" "$TOKENS_FILE")"
fi
if [ "$minutes_left" -lt "$MIN_TOKEN_MINUTES" ]; then
    echo "      토큰이 없거나 곧 만료되어 새로 만듭니다 (1초 정도)"
    TOKENS_FILE="$TOKENS_FILE" node make-tokens.mjs | sed 's/^/      /' || exit 1
else
    echo "      토큰 사용 가능 (만료까지 ${minutes_left}분)"
fi

echo "[4/5] 시작"
started=$(date +%s)
k6 run --quiet --log-format raw \
    -e TOKENS_FILE="$TOKENS_FILE" \
    -e COUNTDOWN_S="$COUNTDOWN_S" \
    -e MAX_DURATION="$MAX_DURATION" \
    -e SUMMARY_DIR="$run_dir" \
    -e LAST_RUN_FILE="$run_dir/run.env" \
    -e MODE="$mode" \
    loadtest-0811.js 2>&1 | grep --line-buffered -v -E 'Request Failed|have been crossed'
k6_status="${PIPESTATUS[0]}"

if [ ! -f "$run_dir/run.env" ]; then
    echo "k6 결과 파일이 없습니다 ($run_dir)" >&2
    exit 1
fi
cp "$run_dir/run.env" results/last-run.env

echo
echo "[5/5] DB, Redis, Kafka 정합성 확인"
./verify-0811.sh "$run_dir/run.env" | tee "$run_dir/verify.txt"
source "$run_dir/run.env"
echo
./prom-peaks.sh $(( K6_START_AT_MS / 1000 - 10 )) "$(date +%s)" | tee "$run_dir/prometheus.txt"

wall=$(( $(date +%s) - started ))
printf 'K6_WALL_S=%s\n' "$wall" >> "$run_dir/run.env"
cp "$run_dir/run.env" "results/last-$mode.env"

secs() {
    awk -v ms="$1" 'BEGIN { printf "%.1fs", ms / 1000 }'
}

line() {
    printf '  %s  오류율 %s%%, 수강신청 p95 %s / p99 %s, 대기열 조회 p95 %s / p99 %s, 마지막 입장 %s, 전체 %d분 %d초%s\n' \
        "$1" "$K6_ERROR_PCT" "$(secs "$K6_ENROLL_P95")" "$(secs "$K6_ENROLL_P99")" "$(secs "$K6_POLL_P95")" "$(secs "$K6_POLL_P99")" \
        "$(secs "$K6_LAST_ADMIT_MS")" $((K6_WALL_S / 60)) $((K6_WALL_S % 60)) "$2"
}

echo
echo "================ 비교 ================"
K6_WALL_S="$wall" line "$mode" ""
if [ -f "results/last-$other.env" ]; then
    ( source "results/last-$other.env"; line "$other" " (이전 실행)" )
else
    echo "  $other 모드 결과가 아직 없습니다. demo/live.sh $other 를 실행하면 여기서 비교합니다."
fi
echo "======================================"
echo "결과 파일: demo/$run_dir"
exit "$k6_status"
