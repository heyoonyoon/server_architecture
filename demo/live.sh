#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh

mode="${1:-}"
case "$mode" in
    A) slots=10000; label="A: 대기열 없음 (동시 입장 한도 10000명)" ;;
    B) slots="${SLOTS:-500}"; label="B: 대기열 있음 (동시 입장 한도 ${slots}명)" ;;
    *)
        echo "사용법: demo/live.sh A            (대기열 없이 3,660명이 한꺼번에)"
        echo "        demo/live.sh B            (대기열, 동시 입장 500명, 한 사람당 3초)"
        echo "        SLOTS=500 demo/live.sh B  (동시 입장 한도 바꾸기)"
        exit 1
        ;;
esac
other="$([ "$mode" = A ] && echo B || echo A)"

TOKENS_FILE="${TOKENS_FILE:-.tokens.json}"
SESSION_S="${SESSION_S:-3}"
STUDENTS="${STUDENTS:-3660}"
USER_ID="${USER_ID:-20100123}"
MAX_DURATION="${MAX_DURATION:-20m}"
MIN_TOKEN_MINUTES=25
run_dir="results/runs/$mode-$(date +%m%d-%H%M%S)"
mkdir -p "$run_dir"

echo "================================================================"
echo "  8/11 수강신청 재현  $label"
echo "================================================================"

guard_real_users "$USER_ID"

echo "[1/5] 초기화"
redis_cmd EVAL "return redis.call('ZREM', KEYS[1], ARGV[1]) + redis.call('ZREM', KEYS[2], ARGV[1]) + redis.call('ZREM', KEYS[3], ARGV[1])" 3 "queue:$SEMESTER" "active:$SEMESTER" "seen:$SEMESTER" "$USER_ID" > /dev/null
./reset.sh | sed 's/^/      /' || exit 1
redis_cmd SET "open:$SEMESTER" "$(( $(now_ms) + 600000 ))" > /dev/null
echo "      준비하는 동안 수강신청 진입을 잠갔습니다"

echo "[2/5] 부하 테스트 학생 토큰 확인"
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

echo "[3/5] 동시 입장 한도와 예상 시간"
redis_cmd SET "max:$SEMESTER" "$slots" > /dev/null
echo "      동시 입장 한도 ${slots}명으로 설정 (Redis max:$SEMESTER, queue-worker가 0.5초마다 읽음)"
awk -v n="$STUDENTS" -v slots="$slots" -v hold="$SESSION_S" -v spread=2 -v poll=2 -v tick=0.5 'BEGIN {
    rate = n / spread
    cycle = poll + hold + tick / 2
    total = (n <= slots) ? spread + cycle : spread + (n / slots) * cycle
    printf "      전체 예상: 학생 %d명, 동시 입장 %d명, 한 자리당 약 %.1f초(조회 간격 %d초 + 머묾 %.1f초), 약 %d초\n", n, slots, cycle, poll, hold, total
    for (r = 0.3; r <= 0.61; r += 0.15) {
        ahead = int(rate * r)
        if (ahead < slots) {
            admit = r + tick
        } else {
            admit = (ahead - slots) / rate + cycle
        }
        seen = r + poll * (int((admit - r) / poll - 1e-9) + 1)
        printf "      브라우저 예상: 오픈 %.2f초 뒤 클릭, 앞에 약 %d명, 서버 입장 처리 오픈 %.1f초 뒤, 화면에 입장 표시 클릭 %.0f초 뒤\n", r, ahead, admit, seen - r
    }
}'

echo "[4/5] 시작"
echo "      가상 학생 ${STUDENTS}명을 띄우는 중입니다. 다 뜨면 5초 카운트다운이 시작됩니다."
started=$(date +%s)
watch_file="$run_dir/user-watch.txt"
(
    clicked="" admitted=""
    end=$(( $(date +%s) + 1800 ))
    while [ "$(date +%s)" -lt "$end" ] && [ -z "$admitted" ]; do
        now="$(now_ms)"
        if [ -z "$clicked" ] && [ -n "$(redis_cmd ZSCORE "queue:$SEMESTER" "$USER_ID")" ]; then
            clicked="$now"
        fi
        if [ -n "$(redis_cmd ZSCORE "active:$SEMESTER" "$USER_ID")" ]; then
            admitted="$now"
            [ -z "$clicked" ] && clicked="$now"
        fi
        printf 'CLICK_MS=%s\nADMIT_MS=%s\n' "$clicked" "$admitted" > "$watch_file"
        sleep 0.2
    done
) &
watcher=$!
k6 run --quiet --log-format raw \
    -e COUNTDOWN_S=5 \
    -e SESSION_S="$SESSION_S" \
    -e STUDENTS="$STUDENTS" \
    -e TOKENS_FILE="$TOKENS_FILE" \
    -e MAX_DURATION="$MAX_DURATION" \
    -e SUMMARY_DIR="$run_dir" \
    -e LAST_RUN_FILE="$run_dir/run.env" \
    -e MODE="$mode" \
    loadtest-0811.js 2>&1 | while IFS= read -r line; do
        case "$line" in
            OPEN_AT_MS=*)
                redis_cmd SET "open:$SEMESTER" "${line#OPEN_AT_MS=}" > /dev/null
                ;;
            *"Request Failed"* | *"have been crossed"*)
                ;;
            *)
                printf '%s\n' "$line"
                ;;
        esac
    done
k6_status="${PIPESTATUS[0]}"
kill "$watcher" 2>/dev/null

if [ ! -f "$run_dir/run.env" ]; then
    redis_cmd DEL "open:$SEMESTER" > /dev/null
    echo "k6 결과 파일이 없습니다 ($run_dir). 오픈 시각 설정은 지웠습니다." >&2
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
echo "================ 브라우저 사용자 ($USER_ID) ================"
if [ -f "$watch_file" ]; then
    source "$watch_file"
    if [ -n "${ADMIT_MS:-}" ] && [ -n "${CLICK_MS:-}" ]; then
        awk -v o="$K6_START_AT_MS" -v c="$CLICK_MS" -v a="$ADMIT_MS" 'BEGIN {
            printf "  진입 클릭               오픈 %.1f초 뒤\n", (c - o) / 1000
            printf "  서버 입장 처리          오픈 %.1f초 뒤 (클릭부터 %.1f초)\n", (a - o) / 1000, (a - c) / 1000
            printf "  화면 입장 표시          서버 처리 후 다음 대기열 조회 때(최대 2초 뒤)\n"
        }'
        printf 'USER_CLICK_AFTER_OPEN_MS=%s\nUSER_ADMIT_AFTER_OPEN_MS=%s\n' "$(( CLICK_MS - K6_START_AT_MS ))" "$(( ADMIT_MS - K6_START_AT_MS ))" >> "$run_dir/run.env"
    else
        echo "  이번 실행에서 $USER_ID 의 대기열 진입이 보이지 않았습니다"
    fi
fi
printf '  전체 소요     오픈부터 마지막 학생 입장까지 %s, 명령 실행부터 결과까지 %d분 %d초\n' "$(awk -v ms="$K6_LAST_ADMIT_MS" 'BEGIN { printf "%.1fs", ms / 1000 }')" $((wall / 60)) $((wall % 60))

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
