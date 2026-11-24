#!/usr/bin/env bash
#
# Unit test for scripts/release-download-table.sh, which generates the Downloads
# section of every release body (release.yml) and of the curated notes
# (release-notes.yml) — markdown that also lands in app_releases.release_notes
# and is rendered by the in-app update dialog. Nothing else exercises it before
# it ships, because release workflows only run on publish.
#
# Guards, in order of what would hurt most:
#
#   * A row for an asset that was never published. Windows ARM64 and Linux ARM64
#     are optional builds, so a table that hardcodes nine rows produces live 404s
#     on any release where one failed.
#   * A pre-release page whose "always latest" links resolve to an older stable
#     build — a downgrade offered as an upgrade.
#   * A version string that is not a version reaching the URLs verbatim.
#   * Column count, because the update dialog renders this at 11.sp in a 480.dp
#     dialog and a fourth column wraps every cell.
#
# Run: bash scripts/test/test-release-download-table.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../release-download-table.sh"

TD="$(mktemp -d)"; trap 'rm -rf "$TD"' EXIT
pass=0; fail=0
ok()  { echo "  ok: $1"; pass=$((pass + 1)); }
bad() { echo "  FAIL: $1" >&2; fail=$((fail + 1)); }

check() { # <desc> <actual> <expected>
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1 (got '$2', want '$3')"; fi
}

# Captures status WITHOUT `|| ...` so a nonzero exit cannot hide.
run() { # <outfile> <args...>
  local out="$1"; shift
  if bash "$SCRIPT" "$@" >"$out" 2>"${out}.err"; then echo 0; else echo $?; fi
}

echo "== version guard =="
for bad_version in "" "v9.4.0" "9.4" "9.4.0 && echo pwned" "9.4.0/../../etc" "9.4.0-nightly"; do
  rc=$(run "$TD/g" --version "$bad_version")
  check "rejects '$bad_version'" "$rc" "1"
done
for good_version in "9.4.0" "10.0.1" "9.5.0-beta.1" "9.5.0-rc.2" "9.5.0-alpha.10"; do
  rc=$(run "$TD/g" --version "$good_version")
  check "accepts '$good_version'" "$rc" "0"
done
rc=$(run "$TD/g" --version 9.4.0 --bogus-flag)
check "rejects an unknown flag" "$rc" "1"

echo "== a flag with no value fails loudly, not silently =="
# A bare `shift 2` here exits 1 under `set -e` with no output at all.
for flag in --version --repo --assets --asset-list; do
  rc=$(run "$TD/g" "$flag")
  check "$flag alone exits 1" "$rc" "1"
  check "$flag alone explains itself" "$(grep -c "requires a value" "$TD/g.err")" "1"
done
rc=$(run "$TD/h" --help)
check "--help exits 0" "$rc" "0"

echo "== full table (no asset filter) =="
run "$TD/full" --version 9.4.0 >/dev/null
check "9 asset rows" "$(grep -c '^| \*\*' "$TD/full")" "9"
check "3 columns in the header" "$(grep -m1 '^| Platform' "$TD/full" | awk -F'|' '{print NF-2}')" "3"
check "9 always-latest links" "$(grep -o 'latest-release?app=boss&download=[^)]*' "$TD/full" | wc -l | tr -d ' ')" "9"
check "no duplicate always-latest links" \
  "$(grep -o 'latest-release?app=boss&download=[^)]*' "$TD/full" | sort -u | wc -l | tr -d ' ')" "9"
check "every row links a versioned asset" "$(grep -c 'releases/download/v9\.4\.0/BOSS-9\.4\.0' "$TD/full")" "9"
check "defaults to the public assets repo" \
  "$(grep -c 'akashjadon04/BossConsole-Releases/releases/download' "$TD/full")" "9"
check "no unresolved shell expansion left in output" "$(grep -c '\${' "$TD/full")" "0"

# The update dialog's inline scanner matches the link alternative first at
# offset 0 and then appends the captured label verbatim (ReleaseNotesMarkdown.kt
# `append(link.groupValues[1])`), so a code span nested in a link label renders
# its backticks as literal characters. A bare code span is fine — that path does
# removeSurrounding("`") — a linked one is not.
check "no backticks inside any link label" \
  "$(grep -oE '\[[^]]*\]\(' "$TD/full" | grep -c '`' || true)" "0"

# This output is committed into docs/release-notes and pushed to
# app_releases.release_notes, so it is the largest single generator of prose in
# the project. House style is a spaced hyphen, not an em-dash.
check "emits no em-dashes" "$(grep -c -- '—' "$TD/full" || true)" "0"

echo "== --repo threading (sync-release rewrites private -> public) =="
run "$TD/priv" --version 9.4.0 --repo akashjadon04/BossConsole >/dev/null
sed 's|akashjadon04/BossConsole|akashjadon04/BossConsole-Releases|g' "$TD/priv" > "$TD/pub"
check "rewrite yields public asset links" "$(grep -c 'akashjadon04/BossConsole-Releases/releases/download' "$TD/pub")" "9"
# The pattern also matches inside its own replacement, so a table that already
# said BossConsole-Releases would become BossConsole-Releases-Releases.
check "rewrite does not double the suffix" "$(grep -c 'BossConsole-Releases-Releases' "$TD/pub")" "0"
check "no private links survive" "$(grep -c 'BossConsole/releases/download' "$TD/pub")" "0"
check "rewrite leaves the edge-function links untouched" \
  "$(diff <(grep -o 'latest-release?[^)]*' "$TD/priv") <(grep -o 'latest-release?[^)]*' "$TD/pub") >/dev/null && echo same || echo differs)" \
  "same"

echo "== optional ARM64: rows are dropped, not left to 404 =="
mkdir -p "$TD/assets"
# Exactly what create-release guarantees: macOS, Windows x64, Linux amd64.
for f in BOSS-9.4.0-Universal.dmg BOSS-9.4.0.msi BOSS-9.4.0-amd64.deb BOSS-9.4.0-amd64.rpm BOSS-9.4.0-amd64.jar; do
  : > "$TD/assets/$f"
done
run "$TD/part" --version 9.4.0 --assets "$TD/assets" >/dev/null
check "only published assets get rows" "$(grep -c '^| \*\*' "$TD/part")" "5"
check "no link to the absent Windows ARM64 msi" "$(grep -c 'BOSS-9\.4\.0-arm64\.msi' "$TD/part")" "0"
check "no link to the absent Linux arm64 deb" "$(grep -c 'BOSS-9\.4\.0-arm64\.deb' "$TD/part")" "0"
check "the published amd64 deb is still listed" "$(grep -c 'BOSS-9\.4\.0-amd64\.deb' "$TD/part")" "1"
# Always-latest links are a standing public contract, independent of which
# builds happened to succeed for this one release.
check "always-latest links are unaffected" \
  "$(grep -o 'latest-release?app=boss&download=[^)]*' "$TD/part" | wc -l | tr -d ' ')" "9"
check "drops are reported on stderr, not silent" "$(grep -c 'omitted rows' "$TD/part.err")" "1"
check "stderr names an omitted asset" "$(grep -c 'BOSS-9\.4\.0-arm64\.msi' "$TD/part.err")" "1"

echo "== --asset-list is equivalent to --assets =="
( cd "$TD/assets" && ls ) > "$TD/list"
run "$TD/bylist" --version 9.4.0 --asset-list "$TD/list" >/dev/null
check "same rows as --assets" "$(diff "$TD/part" "$TD/bylist" >/dev/null && echo same || echo differs)" "same"
# grep -Fxq, not a substring match: BOSS-9.4.0.msi must not satisfy a lookup
# for BOSS-9.4.0-arm64.msi, nor vice versa.
printf 'BOSS-9.4.0-arm64.msi\n' > "$TD/list2"
run "$TD/bylist2" --version 9.4.0 --asset-list "$TD/list2" >/dev/null
check "matches whole names only" "$(grep -c '^| \*\*' "$TD/bylist2")" "1"
check "and it is the arm64 msi" "$(grep -c 'BOSS-9\.4\.0-arm64\.msi' "$TD/bylist2")" "1"
rc=$(run "$TD/g" --version 9.4.0 --assets "$TD/nope")
check "missing --assets dir is an error" "$rc" "1"
rc=$(run "$TD/g" --version 9.4.0 --assets "$TD/assets" --asset-list "$TD/list")
check "both filters at once is an error" "$rc" "1"

echo "== a filter that matches nothing must not emit an empty table =="
# What a stale or failed `gh release view` looks like: names from another version.
printf 'BOSS-1.2.3-Universal.dmg\n' > "$TD/wrong"
run "$TD/none" --version 9.4.0 --asset-list "$TD/wrong" >/dev/null
check "falls back to listing every package" "$(grep -c '^| \*\*' "$TD/none")" "9"
check "and says why on stderr" "$(grep -c 'no listed asset matched' "$TD/none.err")" "1"
check "table is never just a bare header" "$(grep -c '^| \*\*' "$TD/none")" "9"

echo "== pre-release must not advertise an older stable as 'latest' =="
run "$TD/pre" --version 9.5.0-beta.1 --prerelease >/dev/null
check "every always-latest link opts into pre-releases" \
  "$(grep -o 'latest-release?app=boss&download=[^)]*prerelease=true' "$TD/pre" | wc -l | tr -d ' ')" "9"
check "and says so in the copy" "$(grep -c 'including pre-releases' "$TD/pre")" "1"
# The pre-release branch emits its own copy, so asserting only the stable output
# would leave half the generator unchecked.
check "the pre-release copy emits no em-dashes either" "$(grep -c -- '—' "$TD/pre" || true)" "0"
run "$TD/rel" --version 9.4.0 >/dev/null
check "a stable release does not pass prerelease=true" "$(grep -c 'prerelease=true' "$TD/rel")" "0"
check "and says newest stable" "$(grep -c 'newest stable release' "$TD/rel")" "1"

echo ""
echo "passed: $pass   failed: $fail"
[[ "$fail" -eq 0 ]]
