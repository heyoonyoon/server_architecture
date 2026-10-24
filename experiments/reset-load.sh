#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
data_addr="${DATA_ADDR:-192.168.64.33:9200}"
semester="${SEMESTER:-2026-2}"
first="${FIRST_ID:-30000001}"
students="${STUDENTS:-3660}"
last=$(( first + students ))
k6_image="${K6_IMAGE:-grafana/k6:2.3.0}"
compose=(docker compose -f "$root/deploy/infra/docker-compose.yml")

lag="$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
    --describe --group enrollment-consumer 2>/dev/null | awk '$6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }')"
if [ "$lag" != "0" ]; then
    echo "enrollment-consumer LAG 이 $lag 입니다. 0 이 된 뒤에 다시 실행하세요."
    exit 1
fi

out="$(mktemp -d)"
chmod 777 "$out"
trap 'rm -rf "$out"' EXIT

docker run --rm --network host -v "$root:/sa:ro" -v "$out:/out" \
    -e DATA_ADDR="$data_addr" -e SEMESTER="$semester" -e FIRST_ID="$first" -e STUDENTS="$students" \
    -e PROTO_DIR=/sa/Proto/src/main/proto -e SUMMARY_DIR=/out \
    "$k6_image" run -q /sa/experiments/k6/reset-load.js

"${compose[@]}" exec -T -e A="$first" -e B="$last" -e S="$semester" redis sh -c '
    for key in queue:$S active:$S seen:$S; do
        redis-cli ZRANGE "$key" 0 -1 | awk -v a="$A" -v b="$B" "\$1 >= a && \$1 < b" | xargs -r -n 500 redis-cli ZREM "$key" > /dev/null
    done
    for prefix in student:offerings:$S lock:student:$S; do
        redis-cli --scan --pattern "$prefix:*" | awk -F: -v a="$A" -v b="$B" "\$NF >= a && \$NF < b" | xargs -r -n 500 redis-cli DEL > /dev/null
    done'

while read -r id seats; do
    [ -n "$id" ] && "${compose[@]}" exec -T redis redis-cli HSET "seats:$semester" "$id" "$seats" > /dev/null
done < "$out/seats.txt"

"${compose[@]}" exec -T -e A="$first" -e B="$last" -e S="$semester" redis sh -c '
    for key in queue:$S active:$S seen:$S; do
        echo "$key load=$(redis-cli ZRANGE "$key" 0 -1 | awk -v a="$A" -v b="$B" "\$1 >= a && \$1 < b" | wc -l) total=$(redis-cli ZCARD "$key")"
    done
    echo "student:offerings load=$(redis-cli --scan --pattern "student:offerings:$S:*" | awk -F: -v a="$A" -v b="$B" "\$NF >= a && \$NF < b" | wc -l)"
    echo "seats:$S $(redis-cli HGETALL seats:$S | paste -d= - - | sort -n | tr "\n" " ")"'
cat "$out/reset.env"
