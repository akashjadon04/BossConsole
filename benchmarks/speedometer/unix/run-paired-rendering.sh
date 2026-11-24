#!/usr/bin/env bash
#
# Paired OFF_SCREEN vs HARDWARE_ACCELERATED comparison for the fluck browser, on
# macOS or Linux. The counterpart to ../win/run-paired-rendering.ps1.
#
# Runs the two rendering modes ALTERNATELY, N times each, rather than N of one
# followed by N of the other. ../win/WINDOWS.md records why in detail, and it is not
# a theoretical concern: on that machine a sequential A-then-B comparison invented a
# 10-15% difference that vanished — and reversed sign — as soon as the arms were
# interleaved. One unchanged build produced 18.8 through 23.8 across a single
# session. Alternating puts each pair within a couple of minutes of the other, so
# ambient drift hits both arms instead of one.
#
# Everything except the rendering mode is held constant: same build, same switches,
# same forced single-browser-tab layout, same settle time.
#
# Reported per pair AND as the median of the per-pair RATIOS — not a ratio of
# medians, which would average away a pair where the two arms disagreed.
#
# NOTE ON WHAT THIS MEASURES. Speedometer is a throughput benchmark, and on macOS
# throughput is not where OFF_SCREEN costs anything: it already scores 47.9 there,
# ahead of Chrome. The macOS case for HARDWARE is idle CPU and memory, which this
# script cannot see. Run scripts/perf-timeline.py for that arm. Expect this one to
# come out roughly flat on macOS, and treat that as the expected result rather than
# as evidence against the change.
#
# Usage:
#   ./run-paired-rendering.sh [--pairs 3] [--iterations 10] [--settle-seconds 60]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PAIRS=3
ITERATIONS=10
SETTLE_SECONDS=60
RESULTS="results-unix"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pairs) PAIRS="$2"; shift 2 ;;
        --iterations) ITERATIONS="$2"; shift 2 ;;
        --settle-seconds) SETTLE_SECONDS="$2"; shift 2 ;;
        --results) RESULTS="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

for ((i = 1; i <= PAIRS; i++)); do
    for mode in OFF_SCREEN HARDWARE_ACCELERATED; do
        label="pair$i-$(echo "$mode" | tr '[:upper:]' '[:lower:]')"
        "$HERE/run-boss-arm.sh" \
            --label "$label" \
            --repeats 1 \
            --iterations "$ITERATIONS" \
            --settle-seconds "$SETTLE_SECONDS" \
            --rendering-mode "$mode" \
            --results "$RESULTS"
    done
done

echo
echo "============ PAIRED RENDERING-MODE RESULT ============"
python3 - "$HERE/$RESULTS" "$PAIRS" <<'PY'
import json, os, statistics, sys

results_dir, pairs = sys.argv[1], int(sys.argv[2])


def load(path):
    if not os.path.exists(path):
        return None
    with open(path) as fh:
        return json.load(fh)


ratios = []
for i in range(1, pairs + 1):
    osr = load(f"{results_dir}/pair{i}-off_screen-1.json")
    hwa = load(f"{results_dir}/pair{i}-hardware_accelerated-1.json")
    if osr is None or hwa is None:
        print(f"WARNING: pair {i} incomplete - skipping")
        continue
    # A pair with an occluded arm is dropped WHOLE. Keeping the good half would
    # silently pair it with the other mode from a different pair, which is exactly
    # the sequential comparison this script exists to avoid.
    if osr.get("occludedDuringRun") or hwa.get("occludedDuringRun"):
        print(f"WARNING: pair {i} had an occluded run - excluded")
        continue
    ratio = float(hwa["score"]) / float(osr["score"])
    ratios.append(ratio)
    print(
        f"pair{i:<3} OFF_SCREEN {float(osr['score']):>7g}   "
        f"HARDWARE_ACCELERATED {float(hwa['score']):>7g}   gain {round(100 * (ratio - 1))}%"
    )

if not ratios:
    print("No usable pairs.")
    sys.exit(1)

median = statistics.median(ratios)
wins = sum(1 for r in ratios if r > 1)
joined = ", ".join(f"{r:.3f}" for r in ratios)
print()
print(f"median gain from HARDWARE_ACCELERATED: {round(100 * (median - 1))}%  (pairs: {joined})")
print(f"HARDWARE_ACCELERATED won {wins} of {len(ratios)} pairs.")
# Both numbers are printed on purpose. A +5% median that won 2 of 3 pairs is a
# different claim from a +5% median that won 3 of 3, and the median alone hides it.
PY
