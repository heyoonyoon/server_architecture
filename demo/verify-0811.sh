#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source ./lib.sh

env_file="${1:-results/last-run.env}"
if [ ! -f "$env_file" ]; then
    echo "$env_file 이 없습니다. loadtest-0811.js를 먼저 실행하세요." >&2
    exit 1
fi
source "$env_file"

enrolled_sql="SELECT count(*) FROM enrollment e JOIN course_offering o USING (offering_id) WHERE o.course_id LIKE 'LT%' AND o.semester = '$SEMESTER' AND e.status = 'ENROLLED';"

echo "Kafka 컨슈머가 신청 이벤트를 DB에 다 쓸 때까지 기다립니다 (k6 성공 $K6_SUCCESS 건)"
previous=""
stable=0
reached_ms=""
for _ in $(seq 1 600); do
    db_count="$(psql_value "$enrolled_sql")"
    if [ "$db_count" = "$K6_SUCCESS" ]; then
        reached_ms="$(now_ms)"
        break
    fi
    if [ "$db_count" = "$previous" ]; then
        stable=$((stable + 1))
        [ "$stable" -ge 20 ] && break
    else
        stable=0
    fi
    previous="$db_count"
    printf '\r  DB %s / %s 건' "$db_count" "$K6_SUCCESS"
    sleep 0.5
done
echo

over_capacity="$(psql_value "SELECT count(*) FROM (SELECT o.offering_id FROM course_offering o JOIN enrollment e ON e.offering_id = o.offering_id AND e.status = 'ENROLLED' WHERE o.course_id LIKE 'LT%' AND o.semester = '$SEMESTER' GROUP BY o.offering_id, o.capacity HAVING count(*) > o.capacity) x;")"
full_sections="$(psql_value "SELECT count(*) FROM (SELECT o.offering_id FROM course_offering o JOIN enrollment e ON e.offering_id = o.offering_id AND e.status = 'ENROLLED' WHERE o.course_id LIKE 'LT%' AND o.semester = '$SEMESTER' GROUP BY o.offering_id, o.capacity HAVING count(*) = o.capacity) x;")"

values=""
key=""
while IFS= read -r line; do
    if [ -z "$key" ]; then
        key="$line"
    else
        values="$values${values:+,}($key,$line)"
        key=""
    fi
done < <(redis_cmd HGETALL "seats:$SEMESTER")
redis_mismatch="$(psql_value "WITH r (offering_id, remaining) AS (VALUES $values) SELECT count(*) FROM course_offering o JOIN r USING (offering_id) WHERE o.course_id LIKE 'LT%' AND o.semester = '$SEMESTER' AND r.remaining + (SELECT count(*) FROM enrollment e WHERE e.offering_id = o.offering_id AND e.status = 'ENROLLED') <> o.capacity;")"

echo "================ 8/11 재현 정합성 확인 ================"
echo "  k6 신청 성공             $K6_SUCCESS 건"
echo "  DB 신청 행 (ENROLLED)    $db_count 건"
if [ -n "$reached_ms" ]; then
    drain=$(( reached_ms - K6_LAST_SUCCESS_AT_MS ))
    [ "$drain" -lt 0 ] && drain=0
    echo "  Kafka 반영 완료          마지막 성공 응답 후 $((drain / 1000)).$(( (drain % 1000) / 100 ))초 안에 DB 반영 끝 (lag 0, 확인 시점 기준 상한)"
else
    echo "  Kafka 반영               아직 일치하지 않음, 컨슈머 상태 확인 필요"
fi
echo "  정원 초과 강의           $over_capacity 개"
echo "  정원이 꽉 찬 강의        $full_sections 개"
echo "  Redis 좌석 불일치 강의   $redis_mismatch 개 (남은 좌석 + DB 신청 수 = 정원 이어야 함)"
echo "======================================================="
[ "$db_count" = "$K6_SUCCESS" ] && [ "$over_capacity" = "0" ] && [ "$redis_mismatch" = "0" ]
