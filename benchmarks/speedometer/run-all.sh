#!/usr/bin/env bash
# Run Speedometer 3.1 across every browser installed on this machine, one at a
# time. Sequential by design: two browsers benchmarking at once would compete for
# the same cores and both numbers would be meaningless.
#
# Three runners are used depending on what each browser exposes:
#   run-chromium.mjs    CDP over --remote-debugging-port  (Chrome, Comet)
#   run-webdriver.mjs   W3C WebDriver via geckodriver     (Firefox)
#   run-screenshot.mjs  CPU-idle detection + screencapture (Safari, Dia, Atlas)
#
# BOSS's built-in fluck browser is driven separately through the host's own MCP
# tools (browser_navigate / browser_run_js), so it is not launched here.
# No `-e` on purpose: one browser failing must not abandon the rest of a sweep. Failures
# are collected and summarised at the end instead, and the script exits non-zero -- a
# silently missing result file is how a sweep reports "all good" while under-covering.
set -uo pipefail

# Every runner shells to osascript/open/screencapture/sips/pgrep and hardcodes
# /Applications paths. Fail fast rather than emitting a cascade of ENOENT elsewhere.
if [ "$(uname -s)" != "Darwin" ]; then
  echo "This harness is macOS-only (needs osascript, open, screencapture, sips)." >&2
  exit 2
fi

cd "$(dirname "$0")"
ITERATIONS="${ITERATIONS:-10}"
RESULTS="${RESULTS:-results}"
# Number of independent runs per browser. A single Speedometer run is not a
# reliable measurement: background work on the machine (Spotlight indexing a
# newly installed app, a browser's own first-run component updates) can cost a
# browser 20%+ and looks exactly like a slow engine. Repeat and take the median.
REPEATS="${REPEATS:-1}"
mkdir -p "$RESULTS"

# With REPEATS=1 keep the plain <slug>.json name; otherwise suffix each run.
out_path() { # slug run
  if [ "$REPEATS" -eq 1 ]; then echo "$RESULTS/$1.json"; else echo "$RESULTS/$1-run$2.json"; fi
}

# Give the machine a moment to settle between browsers so one run's teardown does
# not eat into the next run's measurement.
settle() { sleep 15; }

run_chromium() { # run name binary port slug
  echo "=============== $2 (CDP) run $1/$REPEATS ==============="
  node run-chromium.mjs --name "$2" --binary "$3" --port "$4" \
    --iterations "$ITERATIONS" --out "$(out_path "$5" "$1")"
}

run_webdriver() { # run name driver port slug
  echo "=============== $2 (WebDriver) run $1/$REPEATS ==============="
  node run-webdriver.mjs --name "$2" --driver "$3" --port "$4" \
    --iterations "$ITERATIONS" --out "$(out_path "$5" "$1")"
}

run_screenshot() { # run name slug [ps-match] [app-path]
  echo "=============== $2 (screenshot) run $1/$REPEATS ==============="
  node run-screenshot.mjs --name "$2" --iterations "$ITERATIONS" \
    --out "$(out_path "$3" "$1")" --shot "$RESULTS/$3-run$1.png" \
    --match "${4:-$2}" --app "${5:-$2}"
}

# Which browsers to run; default is everything installed and drivable.
# Dia needs its one-time onboarding completed by hand before it can be measured,
# so it is opt-in via BROWSERS.
BROWSERS="${BROWSERS:-chrome comet firefox safari atlas}"

dispatch() { # run browser
  case "$2" in
    chrome)  run_chromium  "$1" "Google Chrome" "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" 9222 chrome ;;
    comet)   run_chromium  "$1" "Comet" "/Applications/Comet.app/Contents/MacOS/Comet" 9223 comet ;;
    firefox) run_webdriver "$1" "Firefox" geckodriver 4444 firefox ;;
    safari)  run_screenshot "$1" "Safari" safari "com.apple.WebKit" "Safari" ;;
    atlas)   run_screenshot "$1" "ChatGPT Atlas" atlas "Atlas" "/Applications/ChatGPT Atlas.app" ;;
    dia)     run_screenshot "$1" "Dia" dia "Dia" "/Applications/Dia.app" ;;
    *) echo "unknown browser '$2'" ;;
  esac
}

# Round-robin, NOT browser-by-browser: run 1 of every browser, then run 2, and so
# on. Background load on a real machine drifts over the sweep (an indexer waking
# up, a VM getting busy). Grouping all of a browser's runs together bakes that
# drift into whichever browser happened to run during it -- interleaving spreads
# it across all of them so the medians stay comparable.
FAILED=""
TOTAL=0
for run in $(seq 1 "$REPEATS"); do
  for browser in $BROWSERS; do
    TOTAL=$((TOTAL + 1))
    if ! dispatch "$run" "$browser"; then
      FAILED="$FAILED $browser:run$run"
    fi
    # Skip the settle after the very last run -- nothing follows it to protect.
    if [ "$run" -ne "$REPEATS" ] || [ "$browser" != "${BROWSERS##* }" ]; then
      settle
    fi
  done
done

echo "=============== done ==============="
ls -la "$RESULTS"

if [ -n "$FAILED" ]; then
  echo
  echo "FAILED RUNS ($(echo $FAILED | wc -w | tr -d ' ') of $TOTAL):$FAILED" >&2
  echo "Result files for those runs are missing -- do not treat this sweep as complete." >&2
  exit 1
fi
echo "all $TOTAL runs produced a result file"
