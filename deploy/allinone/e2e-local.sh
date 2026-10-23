#!/usr/bin/env bash
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ALLINONE="$ROOT/deploy/allinone"
COMPOSE=(docker compose -f "$ALLINONE/docker-compose.yml")
PROTO_DIR="$ROOT/Proto/src/main/proto"
GRPCURL_IMAGE=fullstorydev/grpcurl:v1.9.3
NETWORK=sa_default
SEMESTER=2026-2
CAPACITY_OFFERING=12
CAPACITY=5
RACERS=(20100123 20090421 20110512 20100124 20100125 20100323 20080678 20110298 20120808 20080603 20070452 20130091 20110876 20100128 20100131 20130094 20130095)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0

log() { printf '\n== %s\n' "$*"; }
ok() { passed=$((passed + 1)); printf 'PASS %s\n' "$1"; }
bad() {
  failed=$((failed + 1))
  printf 'FAIL %s\n' "$1"
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/     /'; fi
}

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running" >&2
  exit 1
fi
for tool in jq openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required" >&2; exit 1; }
done

if command -v grpcurl >/dev/null 2>&1; then
  GRPCURL=(grpcurl)
  PROTO_PATH="$PROTO_DIR"
  ENVOY=localhost:8080
  DATA=localhost:9200
else
  docker image inspect "$GRPCURL_IMAGE" >/dev/null 2>&1 || docker pull -q "$GRPCURL_IMAGE" >/dev/null
  GRPCURL=(docker run --rm -i --network "$NETWORK" -v "$PROTO_DIR:/proto:ro" "$GRPCURL_IMAGE")
  PROTO_PATH=/proto
  ENVOY=envoy:8080
  DATA=data:9200
fi

call() {
  local target=$1 proto=$2 method=$3 body=$4 token=${5:-}
  shift 5 2>/dev/null || shift $#
  local args=(-plaintext -emit-defaults -import-path "$PROTO_PATH" -proto "$proto" -d "$body")
  if [ -n "$token" ]; then args+=(-H "authorization: Bearer $token"); fi
  "${GRPCURL[@]}" "${args[@]}" "$@" "$target" "serverarchitecture.$method"
}

auth() { call "$ENVOY" auth.proto "AuthService/$1" "$2" ""; }
academic() { call "$ENVOY" academic.proto "AcademicService/$1" "$2" "$3"; }
enrollment() { call "$ENVOY" enrollment.proto "EnrollmentService/$1" "$2" "$3" "${@:4}"; }
data() { call "$DATA" data.proto "data.DataService/$1" "$2" ""; }

error_code() { sed -n 's/^ *Code: //p' "$1" | head -n 1; }

login() {
  local user=$1
  auth Login "{\"user_id\":\"$user\",\"password\":\"$user\"}" 2>"$WORK/login-$user.err" | jq -r '.token // empty'
}

admit() {
  local token=$1 name=$2
  local state
  state=$(enrollment EnterQueue '{}' "$token" 2>"$WORK/enter-$name.err" | jq -r '.state // empty')
  [ -n "$state" ] || return 1
  [ "$state" = ADMITTED ] && return 0
  enrollment WatchQueue '{}' "$token" -max-time 120 >"$WORK/watch-$name.json" 2>"$WORK/watch-$name.err" || true
  [ "$(jq -rs 'last.state // empty' "$WORK/watch-$name.json" 2>/dev/null)" = ADMITTED ]
}

expect_rejected() {
  local label=$1 token=$2 offering=$3 expected=$4
  local out="$WORK/reject-$offering-$RANDOM"
  if enrollment Enroll "{\"offering_id\":$offering}" "$token" >"$out.json" 2>"$out.err"; then
    bad "$label" "unexpected success: $(cat "$out.json")"
    return
  fi
  local code
  code=$(error_code "$out.err")
  if [ "$code" = "$expected" ]; then
    ok "$label ($code: $(sed -n 's/^ *Message: //p' "$out.err"))"
  else
    bad "$label (expected $expected)" "$(cat "$out.err")"
  fi
}

enrolled_ids() {
  data ReadWhere '{"collection":"enrollments","field":"status","value":"ENROLLED"}' 2>"$WORK/data.err" \
    | jq -c '[.items[] | select(.fields.semester == "'"$SEMESTER"'") | .id] | sort'
}

log "build and start"
if [ ! -f "$ALLINONE/.env" ]; then
  printf 'JWT_SECRET=%s\n' "$(openssl rand -base64 48 | tr -d '\n')" >"$ALLINONE/.env"
  echo "created $ALLINONE/.env"
fi
"${COMPOSE[@]}" down -v --remove-orphans
"${COMPOSE[@]}" build || exit 1
if ! "${COMPOSE[@]}" up -d --wait --wait-timeout 300; then
  "${COMPOSE[@]}" ps
  echo "stack did not become healthy" >&2
  exit 1
fi
"${COMPOSE[@]}" ps --format 'table {{.Service}}\t{{.Status}}'

log "wait for login through envoy"
STUDENT_TOKEN=""
for _ in $(seq 1 60); do
  STUDENT_TOKEN=$(login 60220435)
  [ -n "$STUDENT_TOKEN" ] && break
  sleep 2
done
if [ -n "$STUDENT_TOKEN" ]; then ok "login 60220435"; else bad "login 60220435" "$(cat "$WORK/login-60220435.err")"; fi

PROFESSOR_TOKEN=$(login P001)
role=$(auth Login '{"user_id":"P001","password":"P001"}' 2>/dev/null | jq -r '.role // empty')
if [ -n "$PROFESSOR_TOKEN" ] && [ "$role" = PROFESSOR ]; then ok "login P001 as PROFESSOR"; else bad "login P001" "$(cat "$WORK/login-P001.err")"; fi

if auth Login '{"user_id":"60220435","password":"wrong"}' >/dev/null 2>"$WORK/badlogin.err"; then
  bad "wrong password rejected"
else
  [ "$(error_code "$WORK/badlogin.err")" = Unauthenticated ] && ok "wrong password rejected" || bad "wrong password rejected" "$(cat "$WORK/badlogin.err")"
fi

log "academic"
courses=$(academic ListCourses '{}' "$PROFESSOR_TOKEN" 2>"$WORK/courses.err" | jq '.courses | length')
if [ "${courses:-0}" -eq 7 ]; then ok "professor lists 7 courses"; else bad "professor lists courses (got ${courses:-none})" "$(cat "$WORK/courses.err")"; fi
students=$(academic ListStudents '{}' "$PROFESSOR_TOKEN" 2>"$WORK/students.err" | jq '.students | length')
if [ "${students:-0}" -eq 18 ]; then ok "professor lists 18 students"; else bad "professor lists students (got ${students:-none})" "$(cat "$WORK/students.err")"; fi
courses=$(academic ListCourses '{}' "$STUDENT_TOKEN" 2>"$WORK/scourses.err" | jq '.courses | length')
if [ "${courses:-0}" -eq 7 ]; then ok "student lists 7 courses"; else bad "student lists courses (got ${courses:-none})" "$(cat "$WORK/scourses.err")"; fi

log "queue"
if admit "$STUDENT_TOKEN" 60220435; then
  ok "60220435 admitted ($(jq -rsc 'map(.state + ":" + (.position|tostring)) | join(" ")' "$WORK/watch-60220435.json" 2>/dev/null || echo direct))"
else
  bad "60220435 admitted" "$(cat "$WORK/enter-60220435.err" "$WORK/watch-60220435.err" 2>/dev/null)"
fi

offerings=$(enrollment ListOfferings '{}' "$STUDENT_TOKEN" 2>"$WORK/offerings.err" | jq '.offerings | length')
if [ "${offerings:-0}" -eq 8 ]; then ok "list 8 offerings"; else bad "list offerings (got ${offerings:-none})" "$(cat "$WORK/offerings.err")"; fi

log "enroll rules"
if enrollment Enroll '{"offering_id":8}' "$STUDENT_TOKEN" >"$WORK/enroll8.json" 2>"$WORK/enroll8.err"; then
  ok "60220435 enrolls offering 8 ($(jq -r '.message' "$WORK/enroll8.json"))"
else
  bad "60220435 enrolls offering 8" "$(cat "$WORK/enroll8.err")"
fi
expect_rejected "duplicate enroll offering 8" "$STUDENT_TOKEN" 8 AlreadyExists
expect_rejected "prerequisite missing for offering 9" "$STUDENT_TOKEN" 9 Aborted

if enrollment Enroll '{"offering_id":15}' "$STUDENT_TOKEN" >/dev/null 2>"$WORK/enroll15.err" \
  && enrollment CancelEnrollment '{"offering_id":15}' "$STUDENT_TOKEN" >"$WORK/cancel15.json" 2>"$WORK/cancel15.err"; then
  ok "enroll and cancel offering 15 ($(jq -r '.message' "$WORK/cancel15.json"))"
else
  bad "enroll and cancel offering 15" "$(cat "$WORK/enroll15.err" "$WORK/cancel15.err" 2>/dev/null)"
fi

mine=$(enrollment ListMyEnrollments '{}' "$STUDENT_TOKEN" 2>"$WORK/mine.err" | jq -c '[.offerings[].offeringId]')
if [ "$mine" = "[8]" ]; then ok "my enrollments are [8]"; else bad "my enrollments (got ${mine:-none})" "$(cat "$WORK/mine.err")"; fi

CONFLICT_STUDENT=20130094
CONFLICT_TOKEN=$(login "$CONFLICT_STUDENT")
if [ -n "$CONFLICT_TOKEN" ] && admit "$CONFLICT_TOKEN" "$CONFLICT_STUDENT" \
  && enrollment Enroll '{"offering_id":9}' "$CONFLICT_TOKEN" >/dev/null 2>"$WORK/enroll9.err"; then
  ok "$CONFLICT_STUDENT enrolls offering 9 (TUE 09:00-10:30)"
else
  bad "$CONFLICT_STUDENT enrolls offering 9" "$(cat "$WORK/login-$CONFLICT_STUDENT.err" "$WORK/enroll9.err" 2>/dev/null)"
fi
expect_rejected "time conflict offering 10 (TUE 09:00-10:30)" "$CONFLICT_TOKEN" 10 Aborted

NO_PASS_STUDENT=20269999
academic AddStudent "{\"student\":{\"student_id\":\"$NO_PASS_STUDENT\",\"name\":\"No Pass\",\"department\":\"SW\"}}" "$PROFESSOR_TOKEN" >/dev/null 2>"$WORK/addstudent.err"
NO_PASS_TOKEN=$(login "$NO_PASS_STUDENT")
if [ -n "$NO_PASS_TOKEN" ]; then
  expect_rejected "enroll without pass" "$NO_PASS_TOKEN" 8 FailedPrecondition
else
  bad "added student logs in" "$(cat "$WORK/addstudent.err" "$WORK/login-$NO_PASS_STUDENT.err")"
fi
academic DeleteStudent "{\"student_id\":\"$NO_PASS_STUDENT\"}" "$PROFESSOR_TOKEN" >/dev/null 2>&1

log "same student, two conflicting offerings at once"
DOUBLE_STUDENT=20130095
DOUBLE_TOKEN=$(login "$DOUBLE_STUDENT")
DOUBLE_WON=""
if [ -n "$DOUBLE_TOKEN" ] && admit "$DOUBLE_TOKEN" "$DOUBLE_STUDENT"; then
  for offering in 9 10; do
    ( enrollment Enroll "{\"offering_id\":$offering}" "$DOUBLE_TOKEN" >"$WORK/double-$offering.json" 2>"$WORK/double-$offering.err" \
      && touch "$WORK/double-won-$offering" ) &
  done
  wait
  wins=$(ls "$WORK"/double-won-* 2>/dev/null | wc -l | tr -d ' ')
  for offering in 9 10; do [ -f "$WORK/double-won-$offering" ] && DOUBLE_WON=$offering; done
  codes=$(for offering in 9 10; do [ -f "$WORK/double-won-$offering" ] || error_code "$WORK/double-$offering.err"; done | tr '\n' ' ')
  if [ "$wins" -eq 1 ]; then ok "exactly 1 of 2 conflicting enrolls succeeded (won $DOUBLE_WON, other: $codes)"; else bad "conflicting double enroll (wins $wins, codes $codes)"; fi
else
  bad "$DOUBLE_STUDENT admitted"
fi

log "concurrent enroll: ${#RACERS[@]} students into offering $CAPACITY_OFFERING (capacity $CAPACITY)"
declare -a TOKENS
for i in "${!RACERS[@]}"; do
  TOKENS[$i]=$(login "${RACERS[$i]}")
done
for i in "${!RACERS[@]}"; do
  ( admit "${TOKENS[$i]}" "${RACERS[$i]}" && touch "$WORK/admitted-${RACERS[$i]}" ) &
done
wait
admitted=$(ls "$WORK"/admitted-* 2>/dev/null | wc -l | tr -d ' ')
if [ "$admitted" -eq "${#RACERS[@]}" ]; then ok "all $admitted racers admitted"; else bad "racers admitted ($admitted/${#RACERS[@]})"; fi

for i in "${!RACERS[@]}"; do
  (
    if enrollment Enroll "{\"offering_id\":$CAPACITY_OFFERING}" "${TOKENS[$i]}" >"$WORK/race-${RACERS[$i]}.json" 2>"$WORK/race-${RACERS[$i]}.err"; then
      touch "$WORK/won-${RACERS[$i]}"
    fi
  ) &
done
wait
WINNERS=()
for s in "${RACERS[@]}"; do
  [ -f "$WORK/won-$s" ] && WINNERS+=("$s")
done
losers_codes=$(for s in "${RACERS[@]}"; do [ -f "$WORK/won-$s" ] || error_code "$WORK/race-$s.err"; done | sort | uniq -c | tr -s ' ' | tr '\n' ',')
if [ "${#WINNERS[@]}" -eq "$CAPACITY" ]; then
  ok "exactly $CAPACITY successes (${WINNERS[*]-}); rejected: ${losers_codes%,}"
else
  bad "exactly $CAPACITY successes (got ${#WINNERS[@]}); rejected: ${losers_codes%,}"
fi
other_codes=$(for s in "${RACERS[@]}"; do [ -f "$WORK/won-$s" ] || error_code "$WORK/race-$s.err"; done | grep -vx ResourceExhausted | sort -u | tr '\n' ' ')
if [ -z "$other_codes" ]; then ok "every race loser got ResourceExhausted"; else bad "race losers got other codes: $other_codes"; fi

remaining=$(enrollment ListOfferings '{}' "$STUDENT_TOKEN" 2>/dev/null | jq ".offerings[] | select(.offeringId == $CAPACITY_OFFERING) | .remaining")
if [ "${remaining:-x}" = 0 ]; then ok "offering $CAPACITY_OFFERING remaining 0"; else bad "offering $CAPACITY_OFFERING remaining (got ${remaining:-none})"; fi

log "data server after consumer catches up"
expected=$( { echo "60220435:8"; echo "$CONFLICT_STUDENT:9"; [ -n "$DOUBLE_WON" ] && echo "$DOUBLE_STUDENT:$DOUBLE_WON"; for s in ${WINNERS[@]+"${WINNERS[@]}"}; do echo "$s:$CAPACITY_OFFERING"; done; } | jq -Rsc 'split("\n") | map(select(length > 0)) | sort')
actual=""
for _ in $(seq 1 30); do
  actual=$(enrolled_ids)
  [ "$actual" = "$expected" ] && break
  sleep 2
done
if [ "$actual" = "$expected" ]; then
  ok "data enrollments ENROLLED = $(echo "$expected" | jq length) expected rows"
else
  bad "data enrollments ENROLLED" "expected $expected"$'\n'"actual   ${actual:-none} $(cat "$WORK/data.err")"
fi
cancelled=$(data Read '{"collection":"enrollments","id":"60220435:15"}' 2>/dev/null | jq -r '.item.fields.status // empty')
if [ "$cancelled" = CANCELLED ]; then ok "data 60220435:15 is CANCELLED"; else bad "data 60220435:15 status (got ${cancelled:-none})"; fi

log "envoy upstream distribution"
curl -s localhost:9901/clusters | grep -E '^(auth|academic|enrollment)::[^:]+:[0-9]+::rq_total::' | sort

log "data server restart replays the logs"
before_counts=$(for c in users students courses offerings enrollments; do data Count "{\"collection\":\"$c\"}" 2>/dev/null | jq -r '.count // 0'; done | tr '\n' ' ')
"${COMPOSE[@]}" restart data >/dev/null 2>&1
after_ids=""
for _ in $(seq 1 30); do
  after_ids=$(enrolled_ids)
  [ -n "$after_ids" ] && break
  sleep 1
done
after_counts=$(for c in users students courses offerings enrollments; do data Count "{\"collection\":\"$c\"}" 2>/dev/null | jq -r '.count // 0'; done | tr '\n' ' ')
if [ "$after_ids" = "$expected" ] && [ "$before_counts" = "$after_counts" ]; then
  ok "after data restart counts users/students/courses/offerings/enrollments = $after_counts"
else
  bad "data restart" "before $before_counts after $after_counts ids ${after_ids:-none}"
fi
relogin=""
for _ in $(seq 1 20); do
  relogin=$(login 60220435)
  [ -n "$relogin" ] && break
  sleep 1
done
if [ -n "$relogin" ]; then ok "login works after data restart"; else bad "login after data restart" "$(cat "$WORK/login-60220435.err")"; fi

log "one copy of each service down"
admit "$STUDENT_TOKEN" 60220435-failover || true
"${COMPOSE[@]}" stop auth1 academic1 enrollment1 >/dev/null 2>&1
failover_ok=0
failover_total=0
for _ in $(seq 1 10); do
  failover_total=$((failover_total + 4))
  t=$(login 60220435) && [ -n "$t" ] && failover_ok=$((failover_ok + 1))
  academic ListCourses '{}' "$PROFESSOR_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
  enrollment GetQueueStatus '{}' "$STUDENT_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
  enrollment ListMyEnrollments '{}' "$STUDENT_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
done
if [ "$failover_ok" -eq "$failover_total" ]; then ok "with auth1, academic1, enrollment1 stopped: $failover_ok/$failover_total calls succeeded"; else bad "failover $failover_ok/$failover_total calls succeeded"; fi
"${COMPOSE[@]}" up -d --wait auth1 academic1 enrollment1 >/dev/null 2>&1

printf '\n%d passed, %d failed\n' "$passed" "$failed"
[ "$failed" -eq 0 ]
