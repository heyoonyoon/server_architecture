#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

target="${1:-loadtest}"
case "$target" in
    loadtest)
        scope_sql="e.student_id BETWEEN '$LT_FIRST' AND '$LT_LAST'"
        scope_label="부하 테스트 학생($LT_FIRST ~ $LT_LAST)"
        ;;
    demo17)
        scope_sql="(e.student_id BETWEEN '$LT_FIRST' AND '$LT_LAST' OR (o.course_id = '17653' AND o.section = '02'))"
        scope_label="부하 테스트 학생과 17653-02 분반(동시성 데모, 실제 학생 계정 포함)"
        ;;
    *)
        echo "사용법: demo/reset.sh            부하 테스트 학생 데이터만 지움 (실제 계정은 그대로)" >&2
        echo "        demo/reset.sh demo17     위에 더해 17653-02 분반 신청도 지움 (동시성 데모 다시 할 때)" >&2
        exit 1
        ;;
esac

count_sql="SELECT count(*) FROM enrollment e JOIN course_offering o USING (offering_id) WHERE o.semester = '$SEMESTER' AND e.status IN ('ENROLLED', 'CANCELLED') AND $scope_sql;"

echo "[1/4] Kafka 컨슈머가 남은 이벤트를 다 반영할 때까지 기다립니다"
previous=""
stable=0
current=""
for _ in $(seq 1 120); do
    current="$(psql_value "$count_sql")"
    if [ "$current" = "$previous" ]; then
        stable=$((stable + 1))
    else
        stable=0
    fi
    [ "$stable" -ge 2 ] && break
    previous="$current"
    sleep 1
done
echo "      지울 대상 신청 행 $current 건에서 변화 없음"

echo "[2/4] DB에서 $scope_label 의 $SEMESTER 신청 기록을 지웁니다"
psql_value "WITH d AS (DELETE FROM enrollment e USING course_offering o WHERE o.offering_id = e.offering_id AND o.semester = '$SEMESTER' AND e.status IN ('ENROLLED', 'CANCELLED') AND $scope_sql RETURNING 1) SELECT count(*) || '건 삭제' FROM d;" | sed 's/^/      /'

echo "[3/4] Redis에서 부하 테스트 학생의 대기열, 입장권, 신청 목록과 오픈 시각, 동시 입장 한도를 지웁니다"
removed="$(redis_cmd EVAL "local function lt(m) return #m == 8 and m >= ARGV[1] and m <= ARGV[2] end
local n = 0
for k = 1, 3 do
    for _, m in ipairs(redis.call('ZRANGE', KEYS[k], 0, -1)) do
        if lt(m) then n = n + redis.call('ZREM', KEYS[k], m) end
    end
end
for _, k in ipairs(redis.call('KEYS', ARGV[3] .. '3*')) do
    if lt(string.sub(k, #ARGV[3] + 1)) then n = n + redis.call('DEL', k) end
end
n = n + redis.call('DEL', KEYS[4], KEYS[5])
return n" 5 "queue:$SEMESTER" "active:$SEMESTER" "seen:$SEMESTER" "open:$SEMESTER" "max:$SEMESTER" "$LT_FIRST" "$LT_LAST" "student:offerings:$SEMESTER:")"
echo "      Redis 항목 ${removed}개 삭제 (실제 학생 계정의 대기열과 입장권은 그대로)"
if [ "$target" = demo17 ]; then
    demo_offering="$(psql_value "SELECT offering_id FROM course_offering WHERE course_id = '17653' AND section = '02' AND semester = '$SEMESTER';")"
    redis_cmd EVAL "local n = 0
for _, k in ipairs(redis.call('KEYS', ARGV[1] .. '*')) do n = n + redis.call('SREM', k, ARGV[2]) end
return n" 0 "student:offerings:$SEMESTER:" "$demo_offering" | sed 's/^/      17653-02 학생별 신청 목록 정리 /'
fi

echo "[4/4] 남아 있는 DB 신청을 기준으로 좌석 수를 다시 계산합니다"
redis_cmd DEL "seats:$SEMESTER" "ready:$SEMESTER" > /dev/null
for _ in $(seq 1 40); do
    [ "$(redis_cmd EXISTS "ready:$SEMESTER")" = "1" ] && break
    sleep 0.5
done
if [ "$(redis_cmd EXISTS "ready:$SEMESTER")" != "1" ]; then
    echo "      queue-worker가 좌석을 다시 채우지 않았습니다. sa-infra에서 queue-worker 상태를 확인하세요." >&2
    exit 1
fi

demo_offering="$(psql_value "SELECT offering_id FROM course_offering WHERE course_id = '17653' AND section = '02' AND semester = '$SEMESTER';")"
real_left="$(psql_value "SELECT count(*) FROM enrollment e JOIN course_offering o USING (offering_id) WHERE o.semester = '$SEMESTER' AND e.status = 'ENROLLED' AND e.student_id NOT BETWEEN '$LT_FIRST' AND '$LT_LAST';")"
echo "      좌석 해시 강의 수 $(redis_cmd HLEN "seats:$SEMESTER"), 17653-02(id $demo_offering) 남은 좌석 $(redis_cmd HGET "seats:$SEMESTER" "$demo_offering"), 남아 있는 실제 학생 신청 ${real_left}건"
echo "      대기열 $(redis_cmd ZCARD "queue:$SEMESTER")명, 입장권 $(redis_cmd ZCARD "active:$SEMESTER")명 (모두 실제 학생)"
echo "초기화 완료"
