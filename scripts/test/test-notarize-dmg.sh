#!/usr/bin/env bash
#
# Unit test for lib/notarize-dmg.sh notarize_dmg. Runs the function under
# `bash -e` — the mode the release workflows use — because the `set +e` fence
# around the command substitution only matters in that mode, and a
# happy-path-only test would never exercise it.
#
# Apple is faked by putting a stub `xcrun` on PATH, so the real code path runs:
# argument construction, output parsing, the submit-vs-wait decision, and the
# retry accounting. Each scenario's stub records what it was asked to do in
# $TD/calls (subcommands) and $TD/args (full argv), and the assertions read
# those — which is how the central regression is expressed: after a dropped
# poll, attempt 2 must be a `wait` on the existing submission, never a second
# `submit`.
#
# Run: bash scripts/test/test-notarize-dmg.sh
set -e

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/../lib/notarize-dmg.sh"

TD="$(mktemp -d)"; trap 'rm -rf "$TD"' EXIT
mkdir -p "$TD/bin"
PATH="$TD/bin:$PATH"

# Credentials the lib expects; the stub asserts they arrive intact.
export APPLE_ID="ci@example.com" APP_PASSWORD="app-specific-pw" TEAM_ID="TEAMID123"
# No real waiting in tests.
export NOTARIZE_RETRY_DELAY=0 NOTARIZE_STAPLE_DELAY=0
export NOTARIZE_ATTEMPT_TIMEOUT="30m"

DMG="$TD/BOSS-9.2.59.dmg"; echo "not really a dmg" > "$DMG"

pass=0; fail=0
ok()  { echo "  ok: $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $1" >&2; fail=$((fail + 1)); }

SUBMISSION_ID="55346c79-b132-4357-bb42-ad5f3a791418"

# The exact shape of the -1001 timeout from run 30067806575: the upload
# succeeded and Apple reported an id, then the status poll died. `statusCode:
# nil` is what distinguishes it from a verdict.
timeout_output() {
  cat <<EOF
Conducting pre-submission checks for BOSS-9.2.59.dmg and initiating connection to the Apple notary service...
Submission ID received
  id: $SUBMISSION_ID
Successfully uploaded file
  id: $SUBMISSION_ID
Waiting for processing to complete.
Current status: In Progress..........Error: HTTPError(statusCode: nil, error: Error Domain=NSURLErrorDomain Code=-1001 "The request timed out." UserInfo={_kCFStreamErrorCodeKey=60})
EOF
}

accepted_output() {
  cat <<EOF
Successfully uploaded file
  id: $SUBMISSION_ID
Waiting for processing to complete.
Current status: Accepted
Processing complete
  id: $SUBMISSION_ID
  status: Accepted
EOF
}

invalid_output() {
  cat <<EOF
Successfully uploaded file
  id: $SUBMISSION_ID
Waiting for processing to complete.
Processing complete
  id: $SUBMISSION_ID
  status: Invalid
EOF
}

# write_stub <<'BODY' — installs $TD/bin/xcrun. The body sees $1.. as the
# xcrun arguments, $CALLS as the log path, and $N as this call's 1-based index.
write_stub() {
  cat > "$TD/bin/xcrun" <<'PREAMBLE'
#!/usr/bin/env bash
CALLS="$TD/calls"
# The library probes `notarytool submit --help` for --timeout support. Answer
# it, but keep it out of the logs so scenarios assert real work only.
if printf '%s\n' "$@" | grep -qxF -- "--help"; then
  echo "OVERVIEW: Submit an archive to the Apple notary service."
  echo "OPTIONS:"
  echo "  --apple-id <apple-id>"
  [[ -z "${STUB_NO_TIMEOUT:-}" ]] && echo "  --timeout <duration>"
  exit 0
fi
printf '%s\n' "$@" >> "$TD/args"
N=$(( $(wc -l < "$CALLS" 2>/dev/null || echo 0) + 1 ))
# Record the subcommand only; credentials and flags are asserted from $TD/args.
echo "$1 $2" >> "$CALLS"
# Every notarytool call must carry the credentials, or a "transient" failure
# would really be an auth failure retried five times.
case "$1" in
  notarytool)
    for want in "--apple-id" "$APPLE_ID" "--password" "$APP_PASSWORD" "--team-id" "$TEAM_ID"; do
      # `--` matters: the wanted values are themselves flags like --apple-id,
      # which BSD grep would otherwise parse as its own options.
      printf '%s\n' "$@" | grep -qxF -- "$want" || { echo "STUB: missing arg $want" >&2; exit 99; }
    done
    ;;
esac
PREAMBLE
  cat >> "$TD/bin/xcrun"
  chmod +x "$TD/bin/xcrun"
}

# scenario <name> — resets the logs for a fresh stub
scenario() { : > "$TD/calls"; : > "$TD/args"; echo "$1"; }
calls()    { tr '\n' ',' < "$TD/calls" | sed 's/,$//'; }
count()    { grep -c "$1" "$TD/calls" || true; }
run()      { set +e; ( set -e; source "$LIB"; notarize_dmg "$DMG" ) > "$TD/out" 2>&1; rc=$?; set -e; }

export TD SUBMISSION_ID

# ---------------------------------------------------------------- accepted
scenario "accepted on first attempt"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(accepted_output)
OUT
  ;;
  "stapler staple") echo "The staple and validate action worked!" ;;
esac
STUB
run
if [[ $rc == 0 ]] && [[ "$(calls)" == "notarytool submit,stapler staple" ]]; then
  ok "accepted first try → one submit, then staple (rc=0)"
else
  bad "accepted first try (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi
# Each attempt must be bounded: notarytool has no default timeout, so a
# half-open connection would otherwise hang the job to GitHub's 6h ceiling
# instead of failing and letting the retry fire.
if grep -qxF -- "--timeout" "$TD/args" && grep -qxF -- "30m" "$TD/args"; then
  ok "per-attempt --timeout is passed when notarytool advertises it"
else
  bad "expected --timeout 30m in argv: $(tr '\n' ' ' < "$TD/args")"
fi

# ------------------------------------------------- dropped poll → resume wait
# THE regression: a -1001 during polling must resume the submission, not
# re-upload it. Attempt 2 is `notarytool wait`, and there is exactly one submit.
scenario "poll times out, then wait succeeds"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(timeout_output)
OUT
    exit 1 ;;
  "notarytool wait") cat <<'OUT'
$(accepted_output)
OUT
    ;;
  "stapler staple") echo "The staple and validate action worked!" ;;
esac
STUB
run
if [[ $rc == 0 ]] && [[ "$(calls)" == "notarytool submit,notarytool wait,stapler staple" ]]; then
  ok "dropped poll → resumes existing submission, no re-upload (rc=0)"
else
  bad "dropped poll (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi
if [[ "$(count 'notarytool submit')" == 1 ]]; then
  ok "exactly one submit across the retry"
else
  bad "expected exactly one submit, got $(count 'notarytool submit')"
fi
# The resume path carries the retry, so it needs its own bound — `submit`
# advertising --timeout says nothing about `wait`.
if [[ "$(grep -cxF -- '--timeout' "$TD/args")" == 2 ]]; then
  ok "--timeout bounds the resume attempt as well as the submit"
else
  bad "expected --timeout on both calls, saw $(grep -cxF -- '--timeout' "$TD/args")"
fi

# ------------------------------------------------------------ Apple verdict
# A rejection is settled: fail fast with the log, no retry attempts.
scenario "Apple returns Invalid"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(invalid_output)
OUT
    exit 1 ;;
  "notarytool log") echo '{"issues":[{"message":"The binary is not signed."}]}' ;;
esac
STUB
run
if [[ $rc != 0 ]] && [[ "$(calls)" == "notarytool submit,notarytool log" ]]; then
  ok "rejection fails fast and fetches the log (no wait retries)"
else
  bad "rejection (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------------------ auth failure
# An expired app-specific password is not a network problem: retrying it five
# times wastes minutes and the log line would point at the wrong cause.
scenario "credentials rejected by Apple (401)"
write_stub <<'STUB'
if [[ "$1 $2" == "notarytool submit" ]]; then
  echo "Error: HTTP status code: 401. Unable to authenticate, check your credentials."
  exit 1
fi
STUB
run
if [[ $rc != 0 ]] && [[ "$(calls)" == "notarytool submit" ]] && grep -q "credentials or account problem" "$TD/out"; then
  ok "401 fails fast on the first attempt, named as a credential problem"
else
  bad "401 handling (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# --------------------------------------------------- missing credentials
scenario "TEAM_ID missing from the environment"
write_stub <<'STUB'
echo "should not be called" >&2; exit 1
STUB
set +e
( set -e; unset TEAM_ID; source "$LIB"; notarize_dmg "$DMG" ) > "$TD/out" 2>&1
rc=$?
set -e
if [[ $rc != 0 ]] && [[ -z "$(calls)" ]] && grep -q "TEAM_ID" "$TD/out"; then
  ok "missing credential fails fast, by name, without calling Apple"
else
  bad "missing credential (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------- id latch must be anchored
# The latch is sticky, so garbage in it would make every later attempt
# `wait <garbage>` and never recover. An unparseable id must leave it empty so
# the retry re-submits instead.
scenario "unparseable submission id is not latched"
write_stub <<'STUB'
if [[ "$1 $2" == "notarytool submit" ]]; then
  if [[ $N == 1 ]]; then
    echo "Successfully uploaded file"
    echo "Warning: no valid id: <none> reported"
    echo "id: not-a-uuid"
    echo 'Error: HTTPError(statusCode: nil, error: Code=-1001 "The request timed out.")'
    exit 1
  fi
  echo "  id: 55346c79-b132-4357-bb42-ad5f3a791418"
  echo "  status: Accepted"
  exit 0
fi
[[ "$1 $2" == "stapler staple" ]] && echo "The staple and validate action worked!"
STUB
run
if [[ $rc == 0 ]] && [[ "$(count 'notarytool wait')" == 0 ]] && [[ "$(count 'notarytool submit')" == 2 ]]; then
  ok "garbage id line is ignored → re-submits rather than waiting on garbage"
else
  bad "id latch (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# -------------------------------------------- upload dies before an id exists
# No submission to resume, so retrying must re-submit.
scenario "upload fails before Apple reports an id"
write_stub <<STUB
if [[ "\$1 \$2" == "notarytool submit" ]]; then
  if [[ \$N == 1 ]]; then
    echo 'Error: HTTPError(statusCode: nil, error: Error Domain=NSURLErrorDomain Code=-1001 "The request timed out.")'
    exit 1
  fi
  cat <<'OUT'
$(accepted_output)
OUT
  exit 0
fi
[[ "\$1 \$2" == "stapler staple" ]] && echo "The staple and validate action worked!"
STUB
run
if [[ $rc == 0 ]] && [[ "$(calls)" == "notarytool submit,notarytool submit,stapler staple" ]]; then
  ok "no id yet → re-submits, then succeeds (rc=0)"
else
  bad "upload failure (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------------------ flaky stapler
# Stapling also talks to Apple, so it gets the same treatment.
scenario "stapler flakes once"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(accepted_output)
OUT
  ;;
  "stapler staple")
    if [[ \$N == 2 ]]; then echo "Error 68: could not validate ticket" >&2; exit 68; fi
    echo "The staple and validate action worked!" ;;
esac
STUB
run
if [[ $rc == 0 ]] && [[ "$(calls)" == "notarytool submit,stapler staple,stapler staple" ]]; then
  ok "stapler retries after a transient failure (rc=0)"
else
  bad "flaky stapler (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------------- exhausted attempts
# Persistent outage: bounded attempts, then fail — with the log fetched.
scenario "every attempt times out"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(timeout_output)
OUT
    exit 1 ;;
  "notarytool wait") echo 'Error: HTTPError(statusCode: nil, error: Code=-1001 "The request timed out.")'; exit 1 ;;
  "notarytool log") echo "no log available" ;;
esac
STUB
run
if [[ $rc != 0 ]] && [[ "$(count 'notarytool submit')" == 1 ]] && [[ "$(count 'notarytool wait')" == 4 ]] && [[ "$(count 'notarytool log')" == 1 ]]; then
  ok "exhausts 5 bounded attempts (1 submit + 4 resumes) then fails (rc=$rc)"
else
  bad "exhausted attempts (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------------- errexit not leaked
# A local packaging script may not run under errexit; sourcing this lib and
# calling it must not silently arm -e for the rest of that script.
scenario "errexit is not leaked to a caller that had it off"
write_stub <<'STUB'
case "$1 $2" in
  "notarytool submit") echo 'Error: Code=-1001 "The request timed out."'; exit 1 ;;
  "notarytool log") echo "none" ;;
esac
STUB
# Fenced: if the lib DOES leak -e, the inner shell exits non-zero and this
# suite (itself under set -e) would abort silently at the assignment, reporting
# no FAIL at all — and a piped run would then look green.
set +e
leak=$(NOTARIZE_MAX_ATTEMPTS=2 bash -c "source '$LIB'; notarize_dmg '$DMG' >/dev/null 2>&1; case \$- in *e*) echo LEAKED;; *) echo clean;; esac")
set -e
[[ -n "$leak" ]] || leak="inner shell died (errexit leak aborted it)"
if [[ "$leak" == "clean" ]]; then
  ok "caller's errexit setting survives the call ($leak)"
else
  bad "errexit leaked into the caller ($leak)"
fi

# ------------------------------------------ --timeout absent from notarytool
# Never pass a flag the runner's notarytool doesn't know: that would fail every
# attempt instantly and turn a runner-image change into a lost release.
scenario "notarytool without --timeout support"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(accepted_output)
OUT
  ;;
  "stapler staple") echo "The staple and validate action worked!" ;;
esac
STUB
set +e
( set -e; export STUB_NO_TIMEOUT=1; source "$LIB"; notarize_dmg "$DMG" ) > "$TD/out" 2>&1
rc=$?
set -e
if [[ $rc == 0 ]] && ! grep -qxF -- "--timeout" "$TD/args" && grep -q "no --timeout on this runner" "$TD/out"; then
  ok "unsupported --timeout is omitted, with a warning (rc=0)"
else
  bad "timeout probe (rc=$rc args=$(tr '\n' ' ' < "$TD/args"))"; cat "$TD/out" >&2
fi

# ------------------------------------------- id reported, upload never landed
# notarytool prints the submission id BEFORE the upload completes. Latching on
# that would leave every remaining attempt waiting on a submission Apple never
# fully received — each burning its whole timeout, ~2h of 10×-billed runner for
# a verdict that can never arrive, where re-submitting recovers.
scenario "id reported but the upload never completed"
write_stub <<'STUB'
if [[ "$1 $2" == "notarytool submit" ]]; then
  if [[ $N == 1 ]]; then
    echo "Conducting pre-submission checks for BOSS-9.2.59.dmg..."
    echo "Submission ID received"
    echo "  id: 55346c79-b132-4357-bb42-ad5f3a791418"
    echo 'Error: HTTPError(statusCode: nil, error: Code=-1001 "The request timed out.")'
    exit 1
  fi
  echo "Successfully uploaded file"
  echo "  id: 55346c79-b132-4357-bb42-ad5f3a791418"
  echo "  status: Accepted"
  exit 0
fi
[[ "$1 $2" == "stapler staple" ]] && echo "The staple and validate action worked!"
STUB
run
if [[ $rc == 0 ]] && [[ "$(count 'notarytool wait')" == 0 ]] && [[ "$(count 'notarytool submit')" == 2 ]]; then
  ok "id without a completed upload is not latched → re-submits"
else
  bad "premature id (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------ auth error with no HTTP status code
# notarytool rejects a malformed app-specific password client-side, printing
# "Unable to authenticate" and nothing else. A case-SENSITIVE pattern reads
# that as transient and retries it five times while blaming the network.
scenario "Unable to authenticate with no status code"
write_stub <<'STUB'
if [[ "$1 $2" == "notarytool submit" ]]; then
  echo "Error: Unable to authenticate. Check that your app-specific password is correct."
  exit 1
fi
STUB
run
if [[ $rc != 0 ]] && [[ "$(calls)" == "notarytool submit" ]] && grep -q "credentials or account problem" "$TD/out"; then
  ok "bare 'Unable to authenticate' fails fast, not retried as network"
else
  bad "bare auth error (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# --------------------------------------------------------- total time budget
# attempts × per-attempt timeout is not a number anyone recomputes when bumping
# one of them, so total elapsed time is capped independently.
scenario "total budget stops the retries early"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(timeout_output)
OUT
    exit 1 ;;
  "notarytool wait") echo 'Error: Code=-1001 "The request timed out."'; exit 1 ;;
  "notarytool log") echo "no log available" ;;
esac
STUB
set +e
( set -e; export NOTARIZE_TOTAL_BUDGET=0; source "$LIB"; notarize_dmg "$DMG" ) > "$TD/out" 2>&1
rc=$?
set -e
if [[ $rc != 0 ]] && [[ "$(count 'notarytool submit')" == 1 ]] && [[ "$(count 'notarytool wait')" == 0 ]] \
   && grep -q "Total notarization budget" "$TD/out"; then
  ok "elapsed budget stops after the first attempt instead of all five"
else
  bad "total budget (rc=$rc calls=$(calls))"; cat "$TD/out" >&2
fi

# ------------------------------------------------------- recovery hint
# On a terminal transient failure Apple has probably accepted the build; the
# operator should resume that submission, not re-run the whole signed build.
scenario "terminal failure prints the resume command"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(timeout_output)
OUT
    exit 1 ;;
  "notarytool wait") echo 'Error: Code=-1001 "The request timed out."'; exit 1 ;;
  "notarytool log") echo "no log available" ;;
esac
STUB
run
if [[ $rc != 0 ]] && grep -q "xcrun notarytool wait ${SUBMISSION_ID}" "$TD/out" && grep -q "stapler staple" "$TD/out"; then
  ok "exhausted attempts print the wait+staple recovery command with the id"
else
  bad "recovery hint missing (rc=$rc)"; cat "$TD/out" >&2
fi

# --------------------------------------- no recovery hint on a real rejection
# Telling someone to resume a submission Apple rejected would be actively
# misleading.
scenario "rejection does not print a resume hint"
write_stub <<STUB
case "\$1 \$2" in
  "notarytool submit") cat <<'OUT'
$(invalid_output)
OUT
    exit 1 ;;
  "notarytool log") echo '{"issues":[{"message":"The binary is not signed."}]}' ;;
esac
STUB
run
if [[ $rc != 0 ]] && ! grep -q "may still have been accepted" "$TD/out"; then
  ok "rejection omits the resume hint"
else
  bad "rejection printed a resume hint (rc=$rc)"; cat "$TD/out" >&2
fi

# --------------------------------------------------------------- missing DMG
scenario "missing DMG"
write_stub <<'STUB'
echo "should not be called" >&2; exit 1
STUB
set +e; ( set -e; source "$LIB"; notarize_dmg "$TD/nope.dmg" ) > "$TD/out" 2>&1; rc=$?; set -e
if [[ $rc != 0 ]] && [[ -z "$(calls)" ]]; then
  ok "missing DMG fails without calling Apple"
else
  bad "missing DMG (rc=$rc calls=$(calls))"
fi

echo "notarize tests: $pass passed, $fail failed"
[[ "$fail" == 0 ]]
