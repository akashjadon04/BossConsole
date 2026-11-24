#!/usr/bin/env bash
# Decision-table tests for open-jxbrowser-pin-pr.sh.
#
# git and gh are mocked, so nothing here touches a network or a real branch. The
# fixture catalog carries the real neighbouring keys, because the pin rewrite's
# most expensive failure mode is quietly editing `jxbrowser-gradle-plugin` on the
# next line instead.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$repo_root/.github/scripts/open-jxbrowser-pin-pr.sh"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
mock_bin="$test_dir/bin"
mkdir -p "$mock_bin"

# Records what it was asked to do so assertions can read the calls back, and
# refuses anything the script has no business running.
cat > "$mock_bin/git" <<'MOCK_GIT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${MOCK_GIT_LOG:?}"
case "$1" in
  config|add|checkout) exit 0 ;;
  commit)
    printf '%s\n' "$*" > "${MOCK_COMMIT_MSG:?}"
    exit 0
    ;;
  push)
    [[ "${MOCK_PUSH_EXIT:-0}" == "0" ]] || exit "$MOCK_PUSH_EXIT"
    exit 0
    ;;
  diff)
    # --quiet: zero means "no change", which the script treats as a failed edit.
    if [[ "$*" == *"--quiet"* ]]; then
      [[ "${MOCK_DIFF_EMPTY:-false}" == "true" ]] && exit 0
      exit 1
    fi
    if [[ "$*" == *"--numstat"* ]]; then
      printf '%s\tgradle/libs.versions.toml\n' "${MOCK_NUMSTAT:-1	1}"
      exit 0
    fi
    exit 0
    ;;
esac
echo "Unexpected mock git call: $*" >&2
exit 2
MOCK_GIT

cat > "$mock_bin/gh" <<'MOCK_GH'
#!/usr/bin/env bash
set -euo pipefail
case "$1 $2" in
  "pr list")
    printf '%s\n' "${MOCK_PR_LIST:-[]}"
    ;;
  "pr create")
    printf '%s\n' "$*" > "${MOCK_PR_CREATE:?}"
    printf 'https://github.com/akashjadon04/BossConsole/pull/999\n'
    ;;
  *)
    echo "Unexpected mock gh call: $*" >&2
    exit 2
    ;;
esac
MOCK_GH
chmod +x "$mock_bin/git" "$mock_bin/gh"

make_catalog() {
  local pin="$1" path="$2"
  cat > "$path" <<EOF
ktor = "3.5.2"
zxing = "3.5.4"
jxbrowser = "$pin"
jxbrowser-gradle-plugin = "2.1.0"
jna = "5.19.1"
EOF
}

# Runs the script in its own directory so the mocked git never sees the real one.
run_case() {
  local name="$1" pin="$2" version="$3" expected_action="$4"
  shift 4
  local case_dir="$test_dir/$name"
  mkdir -p "$case_dir/gradle"
  local toml="$case_dir/gradle/libs.versions.toml"
  make_catalog "$pin" "$toml"

  local output="$case_dir/output" log="$case_dir/log"
  : > "$case_dir/git.log"

  if ! (
    cd "$case_dir"
    PATH="$mock_bin:$PATH" \
      VERSION="$version" \
      TOML_FILE="gradle/libs.versions.toml" \
      GITHUB_REPOSITORY="akashjadon04/BossConsole" \
      GITHUB_OUTPUT="$output" \
      MOCK_GIT_LOG="$case_dir/git.log" \
      MOCK_COMMIT_MSG="$case_dir/commit.msg" \
      MOCK_PR_CREATE="$case_dir/pr.create" \
      env "$@" bash "$script"
  ) > "$log" 2>&1; then
    echo "FAILED: $name" >&2
    cat "$log" >&2
    exit 1
  fi

  if ! grep -Fqx "pin_action=$expected_action" "$output"; then
    echo "Expected pin_action=$expected_action for $name" >&2
    cat "$output" >&2
    exit 1
  fi
  echo "✓ $name"
}

expect_failure() {
  local name="$1" pin="$2" version="$3"
  shift 3
  local case_dir="$test_dir/$name"
  mkdir -p "$case_dir/gradle"
  make_catalog "$pin" "$case_dir/gradle/libs.versions.toml"
  : > "$case_dir/git.log"

  if (
    cd "$case_dir"
    PATH="$mock_bin:$PATH" \
      VERSION="$version" \
      TOML_FILE="gradle/libs.versions.toml" \
      GITHUB_REPOSITORY="akashjadon04/BossConsole" \
      GITHUB_OUTPUT="$case_dir/output" \
      MOCK_GIT_LOG="$case_dir/git.log" \
      MOCK_COMMIT_MSG="$case_dir/commit.msg" \
      MOCK_PR_CREATE="$case_dir/pr.create" \
      env "$@" bash "$script"
  ) > "$case_dir/log" 2>&1; then
    echo "Expected $name to fail" >&2
    cat "$case_dir/log" >&2
    exit 1
  fi
  echo "✓ $name"
}

open_pr='[{"number":227,"state":"OPEN","url":"https://github.com/x/y/pull/227"}]'
closed_pr='[{"number":227,"state":"CLOSED","url":"https://github.com/x/y/pull/227"}]'
merged_pr='[{"number":227,"state":"MERGED","url":"https://github.com/x/y/pull/227"}]'

# --- the happy path ----------------------------------------------------------
run_case pin_behind_opens_pr 9.4.0 9.4.1 pr_opened

# The bump landed on the right line, and only that line.
happy="$test_dir/pin_behind_opens_pr/gradle/libs.versions.toml"
grep -Fqx 'jxbrowser = "9.4.1"' "$happy" || {
  echo "The pin was not rewritten" >&2; cat "$happy" >&2; exit 1
}
grep -Fqx 'jxbrowser-gradle-plugin = "2.1.0"' "$happy" || {
  echo "The Gradle plugin pin must not move with the engine" >&2; cat "$happy" >&2; exit 1
}
grep -Fqx 'ktor = "3.5.2"' "$happy" || { echo "Unrelated keys must survive" >&2; exit 1; }
grep -q 'chore/jxbrowser-9.4.1' "$test_dir/pin_behind_opens_pr/git.log" || {
  echo "Expected a deterministic branch name" >&2
  cat "$test_dir/pin_behind_opens_pr/git.log" >&2
  exit 1
}
grep -q -- '--force-with-lease' "$test_dir/pin_behind_opens_pr/git.log" || {
  echo "Expected the push to be lease-guarded" >&2; exit 1
}
grep -q '9.4.1' "$test_dir/pin_behind_opens_pr/commit.msg" || {
  echo "Expected the commit message to name the version" >&2; exit 1
}
grep -Fqx 'config user.name Akash Jadon' "$test_dir/pin_behind_opens_pr/git.log" || {
  echo "Expected the project commit identity" >&2
  cat "$test_dir/pin_behind_opens_pr/git.log" >&2
  exit 1
}
# Scoped to the identity lines on purpose: matching "bot" across the whole log
# also matches "both" in the commit body, which is how this assertion first
# passed for the wrong reason.
identity="$(grep '^config user\.' "$test_dir/pin_behind_opens_pr/git.log" || true)"
if grep -qi 'bot' <<< "$identity"; then
  echo "A bot identity must never author these commits: $identity" >&2
  exit 1
fi
grep -Fq -- '--base main' "$test_dir/pin_behind_opens_pr/pr.create" || {
  echo "Expected the PR to target main" >&2; exit 1
}

# --- states that must not open anything --------------------------------------
run_case pin_already_current 9.4.1 9.4.1 up_to_date
run_case downgrade_refused 9.4.1 9.4.0 not_newer
run_case same_version_four_part 9.4.0.1 9.4.0.1 up_to_date
run_case four_part_downgrade_refused 9.4.0.1 9.4.0 not_newer
run_case open_pr_is_not_duplicated 9.4.0 9.4.1 pr_exists MOCK_PR_LIST="$open_pr"
run_case closed_pr_is_respected 9.4.0 9.4.1 declined MOCK_PR_LIST="$closed_pr"
run_case dry_run_decides_only 9.4.0 9.4.1 would_open DRY_RUN=true

# A merged PR for this version is not a reason to stand down: the pin on disk is
# what decides, and if it is still behind then the merge did not move it.
run_case merged_pr_does_not_block 9.4.0 9.4.1 pr_opened MOCK_PR_LIST="$merged_pr"

# Nothing may be pushed on any of the no-op paths.
for name in pin_already_current downgrade_refused open_pr_is_not_duplicated \
  closed_pr_is_respected dry_run_decides_only; do
  if grep -q "push" "$test_dir/$name/git.log" 2>/dev/null; then
    echo "$name must not push" >&2
    cat "$test_dir/$name/git.log" >&2
    exit 1
  fi
done
echo "✓ no_push_on_noop_paths"

# --- numeric, not lexical ----------------------------------------------------
# The bug a string compare ships: "9.4.9" > "9.4.10" lexically, so the pin would
# refuse a real upgrade and sit still while looking healthy.
run_case patch_ten_supersedes_nine 9.4.9 9.4.10 pr_opened
run_case minor_ten_supersedes_nine 9.9.0 9.10.0 pr_opened
run_case four_part_supersedes_three 9.4.0 9.4.0.1 pr_opened

# --- input validation and edit verification ----------------------------------
expect_failure rejects_tag_prefix 9.4.0 v9.4.1
expect_failure rejects_two_part_version 9.4.0 9.4
expect_failure rejects_command_injection 9.4.0 '9.4.1 && echo pwned'
expect_failure rejects_path_traversal 9.4.0 '9.4.1/../../etc'
expect_failure rejects_prerelease 9.4.0 9.4.1-beta.1
expect_failure rejects_empty_version 9.4.0 ''

# A rewrite that produces no diff is a stale anchor, not a success.
expect_failure empty_diff_is_a_failure 9.4.0 9.4.1 MOCK_DIFF_EMPTY=true
# More than one changed line means the pattern caught a neighbour too.
expect_failure multi_line_diff_is_a_failure 9.4.0 9.4.1 MOCK_NUMSTAT='2	2'
# A failed push must fail the step, not report a PR that does not exist.
expect_failure push_failure_is_a_failure 9.4.0 9.4.1 MOCK_PUSH_EXIT=1

# A catalog with no jxbrowser pin at all.
no_pin_dir="$test_dir/missing_pin/gradle"
mkdir -p "$no_pin_dir"
printf 'ktor = "3.5.2"\n' > "$no_pin_dir/libs.versions.toml"
if (
  cd "$test_dir/missing_pin"
  PATH="$mock_bin:$PATH" VERSION=9.4.1 TOML_FILE="gradle/libs.versions.toml" \
    GITHUB_OUTPUT="$test_dir/missing_pin/output" MOCK_GIT_LOG="$test_dir/missing_pin/git.log" \
    bash "$script"
) > "$test_dir/missing_pin/log" 2>&1; then
  echo "Expected a catalog with no pin to fail" >&2
  exit 1
fi
echo "✓ missing_pin_is_a_failure"

# An unreadable catalog path.
if (
  cd "$test_dir"
  PATH="$mock_bin:$PATH" VERSION=9.4.1 TOML_FILE="gradle/nope.toml" \
    GITHUB_OUTPUT="$test_dir/absent.output" MOCK_GIT_LOG="$test_dir/absent.git.log" \
    bash "$script"
) > "$test_dir/absent.log" 2>&1; then
  echo "Expected a missing catalog to fail" >&2
  exit 1
fi
echo "✓ missing_catalog_is_a_failure"

echo "All JxBrowser pin PR tests passed."
