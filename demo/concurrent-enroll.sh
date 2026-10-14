#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh

already="$(psql_value "SELECT count(*) FROM enrollment e JOIN course_offering o USING (offering_id) WHERE o.course_id = '17653' AND o.section = '02' AND o.semester = '$SEMESTER' AND e.status = 'ENROLLED';")"
if [ "$already" != "0" ]; then
    echo "17653-02 분반에 이미 신청이 ${already}건 있습니다. 먼저 demo/reset.sh demo17 을 실행하세요." >&2
    exit 1
fi

result_file="$(mktemp)"
trap 'rm -f "$result_file"' EXIT

RESULT_FILE="$result_file" node concurrent-enroll.mjs
status=$?
[ -s "$result_file" ] || exit "$status"
read -r offering_id success capacity < "$result_file"

echo
echo "Kafka를 거쳐 DB에 기록되기를 기다립니다"
db_count=""
for _ in $(seq 1 20); do
    db_count="$(psql_value "SELECT count(*) FROM enrollment WHERE offering_id = $offering_id AND status = 'ENROLLED';")"
    [ "$db_count" = "$success" ] && break
    sleep 0.5
done
seats="$(redis_cmd HGET "seats:$SEMESTER" "$offering_id")"

echo "  DB 수강신청 행 (ENROLLED)   $db_count 건 (API 성공 $success 건)"
echo "  Redis 남은 좌석             $seats 석 (정원 $capacity)"
if [ "$db_count" = "$success" ] && [ "$((success + seats))" = "$capacity" ]; then
    echo "  정합성                      DB, Redis, API 결과가 모두 일치합니다"
else
    echo "  정합성                      불일치, 확인 필요"
    status=1
fi
exit "$status"
