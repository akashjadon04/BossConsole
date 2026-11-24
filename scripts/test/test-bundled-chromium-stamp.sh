#!/usr/bin/env bash
#
# Guards the bundled-engine version stamp in release.yml.
#
# version.txt is the ONLY cross-platform signal of which Chromium build an engine
# carries: FluckEngine's other check reads a macOS framework layout and makes no
# claim elsewhere. So on Windows and Linux an unstamped bundled engine gets no
# version check at all — it wins first priority, is never questioned, and cannot
# be repaired, because the download writes to the cache the resolver then never
# reaches (BossConsole#123).
#
# Release builds only run on publish, so nothing else exercises these steps before
# they ship. This asserts the invariant by reading the workflow: every site that
# copies branded Chromium into an app image must stamp version.txt in the same
# block. It exists so adding a sixth platform, or reordering an existing step,
# cannot silently drop the stamp.
#
# Run: bash scripts/test/test-bundled-chromium-stamp.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOWS="$HERE/../../.github/workflows/release.yml $HERE/../../.github/workflows/release-lite.yml"

pass=0; fail=0
ok()  { echo "  ok: $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $1" >&2; fail=$((fail + 1)); }

for w in $WORKFLOWS; do
    [[ -f "$w" ]] || { echo "FATAL: $w not found" >&2; exit 1; }
done

# A "bundling site" copies the unpacked branded-chromium into the app image.
# Both spellings appear: POSIX shell on macOS/Linux/Windows-x64, PowerShell on
# Windows-arm64.
BUNDLE_RE='cp -r branded-chromium/\*|Copy-Item -Path .branded-chromium/\*'
# A "stamp" WRITES version.txt — matched on the write itself, not the filename.
# The word alone also appears in the comment above each stamp, so a filename match
# is satisfied by the prose and passes with the actual write deleted. (Verified:
# it did exactly that.) Same trap as matching "rm -rf" inside a comment that says
# "before an irreversible rm -rf".
# Matches a redirect or WriteAllText into any path ending in version.txt. Not tied
# to one variable name: release-lite stamps "$APP_DIR/chromium/version.txt" while
# release.yml uses "$CHROMIUM_DIR/...", and a matcher pinned to the latter reported
# genuinely-stamped sites as unstamped.
STAMP_RE='> *"[^"]*version\.txt"|WriteAllText\("[^"]*version\.txt"'

# while-read rather than mapfile: macOS ships bash 3.2, and a guard that only
# runs on the CI box is a guard nobody can check before pushing.
for WORKFLOW in $WORKFLOWS; do
echo "--- $(basename "$WORKFLOW") ---"
bundle_lines=""
while IFS= read -r n; do
    bundle_lines="$bundle_lines $n"
done < <(grep -nE "$BUNDLE_RE" "$WORKFLOW" | cut -d: -f1)
# shellcheck disable=SC2086
set -- $bundle_lines
bundle_count=$#

if (( bundle_count == 0 )); then
    bad "no bundling sites found — the matcher has drifted from the workflow"
else
    ok "found $bundle_count bundling site(s)"
fi

# Each site must stamp within the same block. 15 lines is generous for the
# copy/cleanup/stamp sequence while still being local to the step.
WINDOW=15
for line in $bundle_lines; do
    end=$(( line + WINDOW ))
    if sed -n "${line},${end}p" "$WORKFLOW" | grep -qE "$STAMP_RE"; then
        ok "bundling site at line $line stamps version.txt"
    else
        bad "bundling site at line $line does NOT stamp version.txt within $WINDOW lines — \
an engine bundled without a stamp gets no version check off macOS"
    fi
done

# Set-Content would prepend a UTF-8 BOM, which a plain string compare against the
# stamp fails on. The Kotlin reader trims whitespace, not a BOM.
if grep -nE "Set-Content.*version\.txt" "$WORKFLOW" >/dev/null 2>&1; then
    bad "PowerShell stamp uses Set-Content — it writes a UTF-8 BOM; use [System.IO.File]::WriteAllText"
else
    ok "no BOM-producing Set-Content used for the stamp"
fi

# The stamp must carry the version the build actually fetched, not a literal.
if grep -E "$STAMP_RE" "$WORKFLOW" | grep -qE 'JXBROWSER_VERSION'; then
    ok "stamps reference JXBROWSER_VERSION rather than a hardcoded value"
else
    bad "no stamp references JXBROWSER_VERSION — a hardcoded version would go stale silently"
fi

# An empty JXBROWSER_VERSION writes an empty version.txt, which the app reads as
# "no stamp" and lets through — silently reverting to the unchecked behaviour on a
# green release build. Every extraction must fail loudly instead.
extractions=$(grep -cE "JXBROWSER_VERSION=\\\$\(grep|\\\$JXBROWSER_VERSION = \\\$matches" "$WORKFLOW" || true)
guards=$(grep -cE '\-z "\$JXBROWSER_VERSION"|Could not extract jxbrowser version' "$WORKFLOW" || true)
if (( guards >= extractions && extractions > 0 )); then
    ok "all $extractions version extraction(s) guarded against an empty result"
else
    bad "only $guards guard(s) for $extractions extraction(s) — an empty version silently disables the stamp"
fi

done

echo
echo "passed: $pass, failed: $fail"
(( fail == 0 )) || exit 1
