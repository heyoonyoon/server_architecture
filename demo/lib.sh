SEMESTER="${SEMESTER:-2026-2}"
REDIS_HOST="${REDIS_HOST:-192.168.64.34}"
REDIS_PORT="${REDIS_PORT:-6379}"
DB_COMPOSE="deploy/db/docker-compose.yml"
SA_DIR="${SA_DIR:-$HOME/sa}"
LT_FIRST=30000001
LT_LAST=30003660

now_ms() {
    perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'
}

psql_run() {
    if [ -f "$SA_DIR/$DB_COMPOSE" ] && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^sa-db-postgres-1$'; then
        (cd "$SA_DIR" && docker compose -f "$DB_COMPOSE" exec -T postgres psql -v ON_ERROR_STOP=1 -P pager=off -U serverarchitecture -d serverarchitecture "$@")
    else
        ssh sa-db "cd ~/sa && docker compose -f $DB_COMPOSE exec -T postgres psql -v ON_ERROR_STOP=1 -P pager=off -U serverarchitecture -d serverarchitecture $*"
    fi
}

psql_value() {
    printf '%s\n' "$1" | psql_run -At -q
}

redis_read_reply() {
    local line value count i
    IFS= read -r -t 5 line <&3 || return 1
    line="${line%$'\r'}"
    case "${line:0:1}" in
        '$')
            if [ "${line:1}" = "-1" ]; then
                echo ""
            else
                IFS= read -r -t 5 value <&3
                echo "${value%$'\r'}"
            fi
            ;;
        '*')
            count="${line:1}"
            for ((i = 0; i < count; i++)); do
                redis_read_reply
            done
            ;;
        *)
            echo "${line:1}"
            ;;
    esac
}

redis_cmd() {
    local arg
    exec 3<>"/dev/tcp/$REDIS_HOST/$REDIS_PORT" || return 1
    {
        printf '*%d\r\n' "$#"
        for arg in "$@"; do
            printf '$%d\r\n%s\r\n' "${#arg}" "$arg"
        done
    } >&3
    redis_read_reply
    exec 3<&-
}

real_users_in_queue() {
    redis_cmd EVAL "local n = 0
for k = 1, 2 do
    for _, m in ipairs(redis.call('ZRANGE', KEYS[k], 0, -1)) do
        if not (#m == 8 and m >= ARGV[1] and m <= ARGV[2]) and m ~= ARGV[3] then n = n + 1 end
    end
end
return n" 2 "queue:$SEMESTER" "active:$SEMESTER" "$LT_FIRST" "$LT_LAST" "${1:-}"
}

guard_real_users() {
    local count
    count="$(real_users_in_queue "${1:-}")"
    if [ "$count" != "0" ] && [ "${FORCE:-0}" != "1" ]; then
        echo "실제 학생 ${count}명이 지금 대기열에 있거나 입장해 있습니다. 끝날 때까지 기다리거나, 그래도 진행하려면 FORCE=1 을 붙이세요." >&2
        exit 1
    fi
}
