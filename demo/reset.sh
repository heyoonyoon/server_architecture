#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

count_sql="SELECT count(*) FROM enrollment e JOIN course_offering o USING (offering_id) WHERE o.semester = '$SEMESTER' AND e.status IN ('ENROLLED', 'CANCELLED');"

echo "[1/4] Kafka 컨슈머가 남은 이벤트를 다 반영할 때까지 기다립니다"
previous=""
stable=0
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
echo "      DB 신청 행 수 $current 에서 변화 없음"

echo "[2/4] DB에서 $SEMESTER 학기 수강신청 기록을 지웁니다"
psql_value "WITH d AS (DELETE FROM enrollment e USING course_offering o WHERE o.offering_id = e.offering_id AND o.semester = '$SEMESTER' AND e.status IN ('ENROLLED', 'CANCELLED') RETURNING 1) SELECT count(*) || '건 삭제' FROM d;" | sed 's/^/      /'

echo "[3/4] Redis 대기열, 입장권, 좌석, 학생별 신청 목록을 비웁니다"
deleted="$(redis_cmd EVAL "local n = 0
for _, k in ipairs(redis.call('KEYS', ARGV[1])) do n = n + redis.call('DEL', k) end
n = n + redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
return n" 4 "queue:$SEMESTER" "active:$SEMESTER" "seats:$SEMESTER" "ready:$SEMESTER" "student:offerings:$SEMESTER:*")"
echo "      Redis 키 ${deleted}개 삭제"

echo "[4/4] queue-worker가 DB 기준으로 좌석을 다시 채울 때까지 기다립니다"
for _ in $(seq 1 40); do
    [ "$(redis_cmd EXISTS "ready:$SEMESTER")" = "1" ] && break
    sleep 0.5
done
if [ "$(redis_cmd EXISTS "ready:$SEMESTER")" != "1" ]; then
    echo "      queue-worker가 프리웜을 하지 않았습니다. sa-infra에서 queue-worker 상태를 확인하세요." >&2
    exit 1
fi

demo_offering="$(psql_value "SELECT offering_id FROM course_offering WHERE course_id = '17653' AND section = '02' AND semester = '$SEMESTER';")"
echo "      좌석 해시 강의 수 $(redis_cmd HLEN "seats:$SEMESTER"), 17653-02(id $demo_offering) 남은 좌석 $(redis_cmd HGET "seats:$SEMESTER" "$demo_offering")"
echo "초기화 완료"
