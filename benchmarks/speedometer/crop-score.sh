#!/usr/bin/env bash
# Crop a full-screen capture down to Speedometer's score card.
#
# run-screenshot.mjs uses `screencapture`, which grabs the entire display -- so a
# raw capture can contain unrelated windows (messages, mail, other work). Those
# raw PNGs are gitignored; only the crops produced here are safe to commit.
#
# Offsets are per-browser because each browser's window lands in a different
# place. Always eyeball the output before committing it.
#
# Usage: ./crop-score.sh results-final/safari-run1.png results/cropped/safari.png [H W OFF_Y OFF_X]
set -euo pipefail

src="$1"
dst="$2"
height="${3:-1300}"
width="${4:-1750}"
offset_y="${5:-250}"
offset_x="${6:-380}"

# sips prints a warning and still exits 0 when its input is missing, so it will
# happily report success having produced nothing. Check the input up front and the
# output afterwards rather than trusting the exit code.
if [ ! -f "$src" ]; then
  echo "crop-score: source '$src' does not exist" >&2
  exit 1
fi

mkdir -p "$(dirname "$dst")"
sips --cropToHeightWidth "$height" "$width" \
     --cropOffset "$offset_y" "$offset_x" \
     "$src" --out "$dst" >/dev/null

if [ ! -s "$dst" ]; then
  echo "crop-score: sips produced no output at '$dst'" >&2
  exit 1
fi
echo "wrote $dst ($(sips -g pixelWidth -g pixelHeight "$dst" | tail -2 | tr -d ' \n'))"
