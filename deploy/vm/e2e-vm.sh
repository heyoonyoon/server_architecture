#!/usr/bin/env bash
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
PROTO_DIR="$ROOT/Proto/src/main/proto"
GRPCURL_IMAGE=fullstorydev/grpcurl:v1.9.3
ENVOY=${ENVOY:-192.168.64.30:8080}
ENVOY_ADMIN=${ENVOY_ADMIN:-192.168.64.30:9901}
DATA=${DATA:-192.168.64.33:9200}
APP2_YML=${APP2_YML:-}
ENVOY2_YAML=${ENVOY2_YAML:-}
VM_SH=${VM_SH:-}
SEMESTER=2026-2
CAPACITY_OFFERING=12
CAPACITY=5
MAIN_STUDENT=20080603
CONFLICT_STUDENT=20130094
DOUBLE_STUDENT=20130095
NO_PASS_STUDENT=${NO_PASS_STUDENT:-30003660}
LOADTEST_PASSWORD=loadtest0811
RACERS=(20100123 20090421 20110512 20100124 20100125 20100323 20080678 20110298 20120808 20080603 20070452 20130091 20110876 20100128 20100131 20130094 20130095)
APP2="docker compose -p sa-app2 -f /tmp/app2.yml --env-file ~/sa/deploy/app/.env"
LB_DIR="~/sa/deploy/lb"

WORK=$(mktemp -d)
APP2_STARTED=0
ENVOY_SWITCHED=0
RESTORED=0
restore_failed=0
trap 'restore; rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM

passed=0
failed=0

log() { printf '\n== %s\n' "$*"; }
ok() { passed=$((passed + 1)); printf 'PASS %s\n' "$1"; }
bad() {
  failed=$((failed + 1))
  printf 'FAIL %s\n' "$1"
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/     /'; fi
}
warn() { restore_failed=1; printf 'WARN %s\n' "$1"; }

for tool in jq curl rsync; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required" >&2; exit 1; }
done
for f in "$APP2_YML" "$ENVOY2_YAML"; do
  [ -n "$f" ] && [ -f "$f" ] || { echo "set APP2_YML and ENVOY2_YAML to existing files" >&2; exit 1; }
done
if [ -n "$VM_SH" ] && [ ! -x "$VM_SH" ]; then
  echo "VM_SH is not executable: $VM_SH" >&2
  exit 1
fi

if command -v grpcurl >/dev/null 2>&1; then
  GRPCURL=(grpcurl)
  PROTO_PATH="$PROTO_DIR"
else
  docker info >/dev/null 2>&1 || { echo "grpcurl or Docker is required" >&2; exit 1; }
  docker image inspect "$GRPCURL_IMAGE" >/dev/null 2>&1 || docker pull -q "$GRPCURL_IMAGE" >/dev/null
  GRPCURL=(docker run --rm -i -v "$PROTO_DIR:/proto:ro" "$GRPCURL_IMAGE")
  PROTO_PATH=/proto
fi

call() {
  local target=$1 proto=$2 method=$3 body=$4 token=${5:-}
  shift 5 2>/dev/null || shift $#
  local args=(-plaintext -emit-defaults -max-msg-sz 67108864 -import-path "$PROTO_PATH" -proto "$proto" -d "$body")
  if [ -n "$token" ]; then args+=(-H "authorization: Bearer $token"); fi
  "${GRPCURL[@]}" "${args[@]}" "$@" "$target" "serverarchitecture.$method"
}

auth() { call "$ENVOY" auth.proto "AuthService/$1" "$2" ""; }
academic() { call "$ENVOY" academic.proto "AcademicService/$1" "$2" "$3"; }
enrollment() { call "$ENVOY" enrollment.proto "EnrollmentService/$1" "$2" "$3" "${@:4}"; }
data() { call "$DATA" data.proto "data.DataService/$1" "$2" ""; }

error_code() { sed -n 's/^ *Code: //p' "$1" | head -n 1; }

password_of() {
  case $1 in
    3[0-9][0-9][0-9][0-9][0-9][0-9][0-9]) echo "$LOADTEST_PASSWORD" ;;
    *) echo "$1" ;;
  esac
}

login() {
  local user=$1
  auth Login "{\"user_id\":\"$user\",\"password\":\"$(password_of "$user")\"}" 2>"$WORK/login-$user.err" | jq -r '.token // empty'
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

TEST_JSON=$(printf '%s\n' "${RACERS[@]}" "$NO_PASS_STUDENT" | jq -Rsc 'split("\n") | map(select(length > 0))')

enrolled_rows() {
  data ReadWhere '{"collection":"enrollments","field":"status","value":"ENROLLED"}' 2>"$WORK/data.err" \
    | jq -c '[.items[] | select(.fields.semester == "'"$SEMESTER"'") | .id] | sort'
}

enrolled_ids() {
  enrolled_rows | jq -c --argjson s "$TEST_JSON" '[.[] | select((split(":")[0]) as $x | $s | index($x))]'
}

data_count() { data Count "{\"collection\":\"$1\"}" 2>/dev/null | jq -r '.count // empty'; }

vm() {
  local host=$1 cmd=$2 timeout=${3:-180}
  printf '\n[%s pane] %s\n' "$host" "$cmd"
  if [ -z "$VM_SH" ]; then
    printf '>>> MANUAL STEP: run the line above in the Orca "VMs" pane for sa-%s, then press Enter <<<\n' "$host"
    read -r _ </dev/tty
    return 0
  fi
  local out rc
  out=$("$VM_SH" "$host" "$cmd" "$timeout")
  printf '%s\n' "$out" | tail -n 6 | sed 's/^/     /'
  rc=$(printf '%s\n' "$out" | sed -n 's/^== Z[0-9]* rc=\([0-9]*\).*/\1/p' | tail -n 1)
  [ "${rc:-1}" = 0 ]
}

healthy_hosts() {
  curl -s "$ENVOY_ADMIN/clusters" | grep -cE '^(auth|academic|enrollment)::[^:]+:[0-9]+::health_flags::healthy$'
}

wait_healthy() {
  local want=$1 got=0
  for _ in $(seq 1 60); do
    got=$(healthy_hosts)
    [ "$got" -eq "$want" ] && return 0
    sleep 2
  done
  echo "envoy healthy hosts $got, expected $want" >&2
  return 1
}

restore() {
  [ "$RESTORED" = 1 ] && return
  RESTORED=1
  log "restore"
  if [ "$ENVOY_SWITCHED" = 1 ]; then
    vm lb "( cd $LB_DIR && cp envoy.yaml.e2e-orig envoy.yaml && rm envoy.yaml.e2e-orig && docker compose restart envoy )" || warn "envoy config not restored"
    wait_healthy 3 || warn "envoy did not report 3 healthy hosts"
  fi
  if [ "$APP2_STARTED" = 1 ]; then
    vm infra "$APP2 down" || warn "sa-app2 not removed"
  fi
  local s t ids o
  for s in "${RACERS[@]}"; do
    t=$(login "$s")
    if [ -z "$t" ] || ! admit "$t" "restore-$s"; then
      warn "restore $s: cannot log in or enter queue"
      continue
    fi
    ids=$(enrollment ListMyEnrollments '{}' "$t" 2>/dev/null | jq -r '.offerings[].offeringId')
    for o in $ids; do
      enrollment CancelEnrollment "{\"offering_id\":$o}" "$t" >/dev/null 2>"$WORK/restore-$s-$o.err" \
        || warn "restore cancel $s:$o $(cat "$WORK/restore-$s-$o.err")"
    done
    [ -n "$ids" ] && printf 'cancelled %s: %s\n' "$s" "$(echo $ids)"
  done
  local left=""
  for _ in $(seq 1 30); do
    left=$(enrolled_ids)
    [ "$left" = "[]" ] && break
    sleep 2
  done
  [ "$left" = "[]" ] && echo "data: no ENROLLED rows left for test students" || warn "data ENROLLED rows left: ${left:-none}"
  t=$(login "${RACERS[0]}")
  if [ -n "$t" ] && admit "$t" "restore-check"; then
    remaining=$(enrollment ListOfferings '{}' "$t" 2>/dev/null | jq ".offerings[] | select(.offeringId == $CAPACITY_OFFERING) | .remaining")
    [ "${remaining:-x}" = "$CAPACITY" ] && echo "offering $CAPACITY_OFFERING remaining $remaining" || warn "offering $CAPACITY_OFFERING remaining ${remaining:-none}, expected $CAPACITY"
  fi
  for s in "${RACERS[@]}"; do
    t=$(login "$s")
    [ -n "$t" ] && enrollment LeaveQueue '{}' "$t" >/dev/null 2>&1
  done
  echo "queue left for ${#RACERS[@]} test students"
}

log "preflight against $ENVOY and $DATA"
probe=$(login "$MAIN_STUDENT")
if [ -z "$probe" ]; then
  RESTORED=1
  echo "cannot log in $MAIN_STUDENT through $ENVOY" >&2
  cat "$WORK/login-$MAIN_STUDENT.err" >&2
  exit 1
fi
existing=$(enrolled_ids)
seat_rows=$(enrolled_rows | jq -c "[.[] | select(endswith(\":$CAPACITY_OFFERING\"))]")
if [ -z "$existing" ] || [ "$existing" != "[]" ] || [ "$seat_rows" != "[]" ]; then
  RESTORED=1
  echo "test students or offering $CAPACITY_OFFERING already have ENROLLED rows" >&2
  echo "test students: ${existing:-unreadable $(cat "$WORK/data.err")}" >&2
  echo "offering $CAPACITY_OFFERING: $seat_rows" >&2
  exit 1
fi
echo "no ENROLLED rows for test students, offering $CAPACITY_OFFERING is empty"

log "start second copy of each service on sa-infra and switch envoy to 2 endpoints"
rsync -q "$APP2_YML" sa-infra:/tmp/app2.yml || exit 1
rsync -q "$ENVOY2_YAML" sa-lb:/tmp/envoy2.yaml || exit 1
APP2_STARTED=1
vm infra "$APP2 up -d --wait --wait-timeout 300" 360 || { echo "sa-app2 did not start" >&2; exit 1; }
ENVOY_SWITCHED=1
vm lb "( cd $LB_DIR && { [ -f envoy.yaml.e2e-orig ] || cp envoy.yaml envoy.yaml.e2e-orig; } && cp /tmp/envoy2.yaml envoy.yaml && docker compose restart envoy )" || exit 1
wait_healthy 6 || exit 1
curl -s "$ENVOY_ADMIN/clusters" | grep -E '^(auth|academic|enrollment)::[^:]+:[0-9]+::health_flags::' | sort

log "wait for login through envoy"
STUDENT_TOKEN=""
for _ in $(seq 1 60); do
  STUDENT_TOKEN=$(login "$MAIN_STUDENT")
  [ -n "$STUDENT_TOKEN" ] && break
  sleep 2
done
if [ -n "$STUDENT_TOKEN" ]; then ok "login $MAIN_STUDENT"; else bad "login $MAIN_STUDENT" "$(cat "$WORK/login-$MAIN_STUDENT.err")"; fi

PROFESSOR_TOKEN=$(login P001)
role=$(auth Login '{"user_id":"P001","password":"P001"}' 2>/dev/null | jq -r '.role // empty')
if [ -n "$PROFESSOR_TOKEN" ] && [ "$role" = PROFESSOR ]; then ok "login P001 as PROFESSOR"; else bad "login P001" "$(cat "$WORK/login-P001.err")"; fi

if auth Login "{\"user_id\":\"$MAIN_STUDENT\",\"password\":\"wrong\"}" >/dev/null 2>"$WORK/badlogin.err"; then
  bad "wrong password rejected"
else
  [ "$(error_code "$WORK/badlogin.err")" = Unauthenticated ] && ok "wrong password rejected" || bad "wrong password rejected" "$(cat "$WORK/badlogin.err")"
fi

log "academic"
want_courses=$(data_count courses)
want_students=$(data_count students)
courses=$(academic ListCourses '{}' "$PROFESSOR_TOKEN" 2>"$WORK/courses.err" | jq '.courses | length')
if [ -n "$want_courses" ] && [ "${courses:-x}" = "$want_courses" ]; then ok "professor lists $courses courses (data count $want_courses)"; else bad "professor lists courses (got ${courses:-none}, data ${want_courses:-none})" "$(cat "$WORK/courses.err")"; fi
students=$(academic ListStudents '{}' "$PROFESSOR_TOKEN" 2>"$WORK/students.err" | jq '.students | length')
if [ -n "$want_students" ] && [ "${students:-x}" = "$want_students" ]; then ok "professor lists $students students (data count $want_students)"; else bad "professor lists students (got ${students:-none}, data ${want_students:-none})" "$(cat "$WORK/students.err")"; fi
courses=$(academic ListCourses '{}' "$STUDENT_TOKEN" 2>"$WORK/scourses.err" | jq '.courses | length')
if [ -n "$want_courses" ] && [ "${courses:-x}" = "$want_courses" ]; then ok "student lists $courses courses"; else bad "student lists courses (got ${courses:-none})" "$(cat "$WORK/scourses.err")"; fi

log "queue"
if admit "$STUDENT_TOKEN" "$MAIN_STUDENT"; then
  ok "$MAIN_STUDENT admitted ($(jq -rsc 'map(.state + ":" + (.position|tostring)) | join(" ")' "$WORK/watch-$MAIN_STUDENT.json" 2>/dev/null || echo direct))"
else
  bad "$MAIN_STUDENT admitted" "$(cat "$WORK/enter-$MAIN_STUDENT.err" "$WORK/watch-$MAIN_STUDENT.err" 2>/dev/null)"
fi

want_offerings=$(data ReadWhere "{\"collection\":\"offerings\",\"field\":\"semester\",\"value\":\"$SEMESTER\"}" 2>/dev/null | jq '.items | length')
offerings=$(enrollment ListOfferings '{}' "$STUDENT_TOKEN" 2>"$WORK/offerings.err" | jq '.offerings | length')
if [ -n "$want_offerings" ] && [ "${offerings:-x}" = "$want_offerings" ]; then ok "list $offerings offerings (data $SEMESTER $want_offerings)"; else bad "list offerings (got ${offerings:-none}, data ${want_offerings:-none})" "$(cat "$WORK/offerings.err")"; fi

log "enroll rules"
if enrollment Enroll '{"offering_id":8}' "$STUDENT_TOKEN" >"$WORK/enroll8.json" 2>"$WORK/enroll8.err"; then
  ok "$MAIN_STUDENT enrolls offering 8 ($(jq -r '.message' "$WORK/enroll8.json"))"
else
  bad "$MAIN_STUDENT enrolls offering 8" "$(cat "$WORK/enroll8.err")"
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

CONFLICT_TOKEN=$(login "$CONFLICT_STUDENT")
if [ -n "$CONFLICT_TOKEN" ] && admit "$CONFLICT_TOKEN" "$CONFLICT_STUDENT" \
  && enrollment Enroll '{"offering_id":9}' "$CONFLICT_TOKEN" >/dev/null 2>"$WORK/enroll9.err"; then
  ok "$CONFLICT_STUDENT enrolls offering 9 (TUE 09:00-10:30)"
else
  bad "$CONFLICT_STUDENT enrolls offering 9" "$(cat "$WORK/login-$CONFLICT_STUDENT.err" "$WORK/enroll9.err" 2>/dev/null)"
fi
expect_rejected "time conflict offering 10 (TUE 09:00-10:30)" "$CONFLICT_TOKEN" 10 Aborted

NO_PASS_TOKEN=$(login "$NO_PASS_STUDENT")
if [ -n "$NO_PASS_TOKEN" ]; then
  enrollment LeaveQueue '{}' "$NO_PASS_TOKEN" >/dev/null 2>&1
  expect_rejected "enroll without pass ($NO_PASS_STUDENT)" "$NO_PASS_TOKEN" 8 FailedPrecondition
else
  bad "$NO_PASS_STUDENT logs in" "$(cat "$WORK/login-$NO_PASS_STUDENT.err")"
fi

log "same student, two conflicting offerings at once"
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
expected=$( { echo "$MAIN_STUDENT:8"; echo "$CONFLICT_STUDENT:9"; [ -n "$DOUBLE_WON" ] && echo "$DOUBLE_STUDENT:$DOUBLE_WON"; for s in ${WINNERS[@]+"${WINNERS[@]}"}; do echo "$s:$CAPACITY_OFFERING"; done; } | jq -Rsc 'split("\n") | map(select(length > 0)) | sort')
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
cancelled=$(data Read "{\"collection\":\"enrollments\",\"id\":\"$MAIN_STUDENT:15\"}" 2>/dev/null | jq -r '.item.fields.status // empty')
if [ "$cancelled" = CANCELLED ]; then ok "data $MAIN_STUDENT:15 is CANCELLED"; else bad "data $MAIN_STUDENT:15 status (got ${cancelled:-none})"; fi

log "envoy upstream distribution"
curl -s "$ENVOY_ADMIN/clusters" | grep -E '^(auth|academic|enrollment)::[^:]+:[0-9]+::rq_total::' | sort

log "data server restart replays the logs"
before_counts=$(for c in users students courses offerings enrollments; do data_count "$c"; done | tr '\n' ' ')
vm db "( cd ~/sa/deploy/db && docker compose restart data )" || bad "data restart command failed"
after_ids=""
for _ in $(seq 1 120); do
  after_ids=$(enrolled_ids)
  [ -n "$after_ids" ] && [ "$after_ids" != "[]" ] && break
  sleep 1
done
after_counts=$(for c in users students courses offerings enrollments; do data_count "$c"; done | tr '\n' ' ')
if [ "$after_ids" = "$expected" ] && [ "$before_counts" = "$after_counts" ]; then
  ok "after data restart counts users/students/courses/offerings/enrollments = $after_counts"
else
  bad "data restart" "before $before_counts after $after_counts ids ${after_ids:-none}"
fi
relogin=""
for _ in $(seq 1 60); do
  relogin=$(login "$MAIN_STUDENT")
  [ -n "$relogin" ] && break
  sleep 1
done
if [ -n "$relogin" ]; then ok "login works after data restart"; else bad "login after data restart" "$(cat "$WORK/login-$MAIN_STUDENT.err")"; fi

log "one copy of each service down"
admit "$STUDENT_TOKEN" "$MAIN_STUDENT-failover" || true
vm infra "$APP2 stop auth academic enrollment" || bad "stop sa-app2 command failed"
failover_ok=0
failover_total=0
for _ in $(seq 1 10); do
  failover_total=$((failover_total + 4))
  t=$(login "$MAIN_STUDENT") && [ -n "$t" ] && failover_ok=$((failover_ok + 1))
  academic ListCourses '{}' "$PROFESSOR_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
  enrollment GetQueueStatus '{}' "$STUDENT_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
  enrollment ListMyEnrollments '{}' "$STUDENT_TOKEN" >/dev/null 2>&1 && failover_ok=$((failover_ok + 1))
done
if [ "$failover_ok" -eq "$failover_total" ]; then ok "with sa-app2 auth, academic, enrollment stopped: $failover_ok/$failover_total calls succeeded"; else bad "failover $failover_ok/$failover_total calls succeeded"; fi

restore

printf '\n%d passed, %d failed\n' "$passed" "$failed"
[ "$restore_failed" -eq 0 ] || echo "restore incomplete, see WARN lines"
[ "$failed" -eq 0 ] && [ "$restore_failed" -eq 0 ]
