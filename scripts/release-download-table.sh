#!/usr/bin/env bash
#
# release-download-table.sh
#
# Emits the "Downloads" section of a release body as markdown: a table of this
# release's assets, followed by links that always resolve to the newest release.
#
# Two workflows publish release bodies and both need this section identical —
# release.yml writes the placeholder body, and release-notes.yml substitutes it
# into the curated notes. Generating it from one script is what keeps them from
# drifting apart, which is how the table ended up listing bare filenames with no
# links at all.
#
# Two things this must not do:
#
#   * Link an asset that was never published. Windows ARM64 and Linux ARM64 are
#     optional builds — create-release requires only macos-arm64, linux-amd64 and
#     windows (release.yml), and the copy steps say "tolerate missing" out loud.
#     A row that looks actionable and 404s is worse than no row, so pass
#     --assets/--asset-list and rows without a matching file are dropped (and
#     reported on stderr, never silently).
#
#   * Point a pre-release page at an older stable build. The edge function
#     excludes pre-releases unless asked, so without --prerelease the "always
#     latest" links on a 9.5.0-beta.1 page would resolve to 9.4.0 — a downgrade,
#     presented as though it were newer.
#
# The table stays at three columns on purpose: this markdown is also rendered by
# the in-app update dialog, inside a 480.dp dialog at 11.sp with equal-weight
# columns, where a fourth column wraps every cell.
#
# Usage:
#   release-download-table.sh --version <X.Y.Z> [options]
#
#     --version <v>      release version, without a leading "v" (required)
#     --repo <o/n>       repo whose release assets to link
#                        (default: akashjadon04/BossConsole-Releases;
#                        release.yml passes $GITHUB_REPOSITORY so that
#                        sync-release.yml's private->public rewrite applies)
#     --assets <dir>     only list assets present in this directory
#     --asset-list <f>   only list assets named in this file, one per line
#     --prerelease       this release is a pre-release
#
# Example:
#   release-download-table.sh --version 9.4.0 --assets ./release-assets

set -euo pipefail

VERSION=""
ASSET_REPO="akashjadon04/BossConsole-Releases"
ASSETS_DIR=""
ASSET_LIST=""
IS_PRERELEASE=false

usage() {
  echo "Usage: $(basename "$0") --version <X.Y.Z> [--repo <owner/name>] [--assets <dir>] [--asset-list <file>] [--prerelease]" >&2
}

# A bare `shift 2` on a flag given without a value fails under `set -e` and
# exits with no message at all, which is a confusing way for a release job to
# die.
need_value() { # <flag> <value>
  if [[ -z "${2:-}" ]]; then
    echo "Error: $1 requires a value" >&2
    usage
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)    need_value "$1" "${2:-}"; VERSION="$2"; shift 2 ;;
    --repo)       need_value "$1" "${2:-}"; ASSET_REPO="$2"; shift 2 ;;
    --assets)     need_value "$1" "${2:-}"; ASSETS_DIR="$2"; shift 2 ;;
    --asset-list) need_value "$1" "${2:-}"; ASSET_LIST="$2"; shift 2 ;;
    --prerelease) IS_PRERELEASE=true; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            echo "Error: unknown argument '$1'" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$VERSION" ]]; then
  echo "Error: --version is required" >&2
  usage
  exit 1
fi

# Anchored at both ends. An open tail would admit "9.4.0 && anything" or a
# trailing newline and paste it verbatim into every URL below — silently wrong
# output from the one guard whose job is preventing that. Matches the form
# sync-release.yml already validates.
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-(alpha|beta|rc)\.[0-9]+)?$ ]]; then
  echo "Error: '$VERSION' is not a version. Pass it without a leading 'v'." >&2
  exit 1
fi

if [[ -n "$ASSETS_DIR" && -n "$ASSET_LIST" ]]; then
  echo "Error: pass at most one of --assets and --asset-list" >&2
  exit 1
fi

if [[ -n "$ASSETS_DIR" && ! -d "$ASSETS_DIR" ]]; then
  echo "Error: --assets directory not found: $ASSETS_DIR" >&2
  exit 1
fi

if [[ -n "$ASSET_LIST" && ! -f "$ASSET_LIST" ]]; then
  echo "Error: --asset-list file not found: $ASSET_LIST" >&2
  exit 1
fi

ASSET_URL="https://github.com/${ASSET_REPO}/releases/download/v${VERSION}"
LATEST_API="https://api.risaboss.com/functions/v1/latest-release?app=boss"

# True when no filter was given (list everything) or the asset is present.
asset_published() {
  local name="$1"
  if [[ -n "$ASSETS_DIR" ]]; then
    [[ -e "${ASSETS_DIR}/${name}" ]]
  elif [[ -n "$ASSET_LIST" ]]; then
    grep -Fxq "$name" "$ASSET_LIST"
  else
    return 0
  fi
}

# platform | architecture | short label | asset filename | download= | arch=
ROWS=(
  "**macOS**|Universal (Apple Silicon + Intel)|macOS DMG|BOSS-${VERSION}-Universal.dmg|dmg|"
  "**Windows**|x64|Windows x64|BOSS-${VERSION}.msi|msi|"
  "**Windows**|ARM64|Windows ARM64|BOSS-${VERSION}-arm64.msi|msi|arm64"
  "**Linux DEB**|AMD64 (x86_64)|Linux DEB amd64|BOSS-${VERSION}-amd64.deb|deb|amd64"
  "**Linux DEB**|ARM64 (aarch64)|Linux DEB arm64|BOSS-${VERSION}-arm64.deb|deb|arm64"
  "**Linux RPM**|AMD64 (x86_64)|Linux RPM amd64|BOSS-${VERSION}-amd64.rpm|rpm|amd64"
  "**Linux RPM**|ARM64 (aarch64)|Linux RPM arm64|BOSS-${VERSION}-arm64.rpm|rpm|arm64"
  "**Linux JAR**|AMD64 (x86_64)|Linux JAR amd64|BOSS-${VERSION}-amd64.jar|jar|amd64"
  "**Linux JAR**|ARM64 (aarch64)|Linux JAR arm64|BOSS-${VERSION}-arm64.jar|jar|arm64"
)

latest_link() {
  local pkg="$1" arch_param="$2" url
  url="${LATEST_API}&download=${pkg}"
  [[ -n "$arch_param" ]] && url="${url}&arch=${arch_param}"
  # Without this a pre-release page's "always latest" links resolve to the
  # newest *stable*, i.e. backwards.
  [[ "$IS_PRERELEASE" == true ]] && url="${url}&prerelease=true"
  echo "$url"
}

rows_out=""
latest_out=""
omitted=""

build_rows() { # <apply_filter>
  local apply_filter="$1" row platform arch label asset pkg arch_param
  rows_out=""
  omitted=""
  for row in "${ROWS[@]}"; do
    IFS='|' read -r platform arch label asset pkg arch_param <<< "$row"
    if [[ "$apply_filter" == true ]] && ! asset_published "$asset"; then
      omitted="${omitted}${asset} "
      continue
    fi
    # No backticks inside the link label. The update dialog renders this
    # markdown, and its inline scanner matches the link alternative first at
    # offset 0, then appends the captured label verbatim — a code span nested in
    # a link label therefore shows its backticks as literal characters, styled
    # as a link. A bare code span is fine (it strips them), a linked one is not.
    rows_out="${rows_out}| ${platform} | ${arch} | [${asset}](${ASSET_URL}/${asset}) |"$'\n'
  done
}

for row in "${ROWS[@]}"; do
  IFS='|' read -r platform arch label asset pkg arch_param <<< "$row"
  latest_out="${latest_out}[${label}]($(latest_link "$pkg" "$arch_param")) · "
done

filter_active=false
if [[ -n "$ASSETS_DIR" || -n "$ASSET_LIST" ]]; then
  filter_active=true
fi
build_rows "$filter_active"

# Matching nothing means the asset listing is wrong, not that the release
# shipped no packages — a release always has at least the three required
# builds. An empty table renders as a bare header, so list everything and say
# why rather than publish that.
if [[ -z "$rows_out" && "$filter_active" == true ]]; then
  echo "Warning: no listed asset matched this release; listing all packages instead" >&2
  build_rows false
fi

echo "## 📦 Downloads"
echo ""
echo "| Platform | Architecture | Package |"
echo "|----------|--------------|---------|"
printf '%s' "$rows_out"
echo ""

if [[ "$IS_PRERELEASE" == true ]]; then
  echo "**Always latest** - newest release including pre-releases, resolved server-side, so"
else
  echo "**Always latest** - newest stable release, resolved server-side, so these stay"
fi
echo "correct in a bookmark and need no API key:"
echo ""
echo "${latest_out% · }"
echo ""
echo "Release metadata - version, every asset, sha256 checksums - is at [?app=boss](${LATEST_API})."

# Report drops on stderr: an omitted row is invisible in the output, and
# "the ARM64 build failed" must not read as "this release has no ARM64".
if [[ -n "$omitted" ]]; then
  echo "Note: omitted rows for assets not present in this release: ${omitted% }" >&2
fi
