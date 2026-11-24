#!/usr/bin/env bash
#
# Measure the BOSS fluck browser on Speedometer 3.1 under one configuration, on
# macOS or Linux. The counterpart to ../win/run-boss-arm.ps1, and deliberately a
# close port of it: the two must be comparable, so the settle time, the forced
# single-tab layout and the fresh-process-per-repeat rule are all the same.
#
# One "arm" = one (rendering mode, extra switches) pair, measured $REPEATS times.
# Each repeat launches a FRESH BOSS process, because Chromium switches and the
# rendering mode are both read once at engine creation — reusing a process would
# silently measure the previous arm.
#
# The BOSS under test is a dev-mode instance (BOSS_DEV_MODE=true -> ~/.boss_debug),
# so it cannot lock, mutate or be confused with the operator's own ~/.boss install,
# which may be running at the same time. Process cleanup matches on this worktree's
# executable path for the same reason: it must never kill the operator's BOSS.
#
# The scoring harness is ../win/SpeedometerCdp.java, reused as-is rather than
# reimplemented. It is single-file Java on java.net.http with no platform code, and
# its --attach mode is the only way to score a fluck tab: the tab lives inside the
# BOSS process, so it cannot be spawned like a browser binary. Reading the score out
# of Speedometer's own DOM the same way on every platform is the point — a second
# implementation would make the numbers incomparable.
#
# CAUTION on EXTRA: Chromium's --enable-features / --disable-features are NOT
# additive; the last occurrence wins. An arm passing its own --enable-features
# REPLACES the platform default (on Linux, the VA-API set), so restate what you
# want to keep.
#
# Usage:
#   ./run-boss-arm.sh --label baseline --rendering-mode OFF_SCREEN --repeats 3
#   ./run-boss-arm.sh --label nobg --extra "--disable-renderer-backgrounding"
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKTREE_ROOT="$(cd "$HERE/../../.." && pwd)"

LABEL=""
EXTRA=""
REPEATS=3
ITERATIONS=10
PORT=9222
SETTLE_SECONDS=60
# Pinned explicitly rather than left to the platform default, so an arm always
# measures the mode it claims to. HARDWARE_ACCELERATED is now the default on every
# platform (see JxBrowserConfig.renderingMode); OFF_SCREEN is the old behaviour.
RENDERING_MODE="HARDWARE_ACCELERATED"
RESULTS="results-unix"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --label) LABEL="$2"; shift 2 ;;
        --extra) EXTRA="$2"; shift 2 ;;
        --repeats) REPEATS="$2"; shift 2 ;;
        --iterations) ITERATIONS="$2"; shift 2 ;;
        --port) PORT="$2"; shift 2 ;;
        --settle-seconds) SETTLE_SECONDS="$2"; shift 2 ;;
        --rendering-mode) RENDERING_MODE="$2"; shift 2 ;;
        --results) RESULTS="$2"; shift 2 ;;
        # No silent defaulting: a mistyped flag that was ignored would produce a
        # result labelled as something it is not, which is worse than not running.
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$LABEL" ]] || { echo "--label is required" >&2; exit 2; }
case "$RENDERING_MODE" in
    OFF_SCREEN|HARDWARE_ACCELERATED) ;;
    *) echo "--rendering-mode must be OFF_SCREEN or HARDWARE_ACCELERATED" >&2; exit 2 ;;
esac

case "$(uname -s)" in
    Darwin)
        PLATFORM="mac"
        # The binary inside the bundle, NOT `open -a`. `open` hands the launch to
        # launchd, which does not inherit this shell's environment — so every
        # BOSS_* variable this script sets would be silently dropped and the arm
        # would measure the platform default instead of what it says it does.
        APP_BIN="$WORKTREE_ROOT/composeApp/build/compose/binaries/main/app/BOSS.app/Contents/MacOS/BOSS"
        ;;
    Linux)
        PLATFORM="linux"
        APP_BIN="$WORKTREE_ROOT/composeApp/build/compose/binaries/main/app/BOSS/bin/BOSS"
        ;;
    *) echo "unsupported platform: $(uname -s) (this is the macOS/Linux harness)" >&2; exit 2 ;;
esac

if [[ ! -x "$APP_BIN" ]]; then
    echo "BOSS not built at $APP_BIN - run: ./gradlew :composeApp:createDistributable" >&2
    exit 1
fi

HARNESS_OUT="$HERE/../win/out"
if [[ ! -f "$HARNESS_OUT/SpeedometerCdp.class" ]]; then
    echo "Compiling the CDP harness -> $HARNESS_OUT"
    mkdir -p "$HARNESS_OUT"
    javac -d "$HARNESS_OUT" "$HERE/../win/Json.java" "$HERE/../win/SpeedometerCdp.java"
fi

LAST_SESSION="$HOME/Documents/BOSS/workspaces/Last_Session.json"
BACKUP="$LAST_SESSION.preperf-backup"
# The operator's own Last Session lives at the path this script overwrites. Keep one
# pristine copy so it can always be put back.
#
# HAD_NO_SESSION is the case a conditional backup alone loses. The fixture is written
# unconditionally before every launch, but with no pre-existing file there is nothing to
# back up, so restore_session had nothing to do and the harness single-tab layout became
# the operator's session permanently - the opposite of "put it back on ANY exit".
HAD_NO_SESSION=false
if [[ -f "$LAST_SESSION" ]]; then
    if [[ ! -f "$BACKUP" ]]; then
        cp "$LAST_SESSION" "$BACKUP"
        echo "Backed up your Last Session to $BACKUP"
    fi
else
    HAD_NO_SESSION=true
fi

# Only ever match BOSS processes launched from THIS worktree's build output.
stop_dev_boss() {
    local pids
    pids="$(pgrep -f "^$APP_BIN" 2>/dev/null || true)"
    if [[ -n "$pids" ]]; then
        # shellcheck disable=SC2086
        kill $pids 2>/dev/null || true
        sleep 2
        # shellcheck disable=SC2086
        kill -9 $pids 2>/dev/null || true
        # Chromium children outlive a killed host briefly and hold the profile lock;
        # the next launch would then fall back to a temp profile and measure
        # something subtly different.
        sleep 3
    fi
    # A killed instance leaves its single-instance descriptor behind. The next launch
    # finds it, forwards its command line to a process that no longer exists, and
    # exits — which shows up here as "DevTools never came up". Dev dir only.
    rm -f "$HOME/.boss_debug/run/single-instance"* 2>/dev/null || true
}

wait_devtools() {
    local deadline=$((SECONDS + $1))
    while ((SECONDS < deadline)); do
        if curl -fsS --max-time 3 "http://127.0.0.1:$PORT/json/version" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# The PID whose window belongs to THIS worktree's build, or empty.
#
# Never match the window by process name. The operator's own BOSS is very likely
# running — that is the whole reason this harness uses dev mode — and it is also called
# "BOSS", so `first process whose name is "BOSS"` resolves to whichever the
# accessibility API happens to list first. Observed doing exactly that: it returned
# /Applications/BOSS.app and repositioned the operator's window while the benchmark
# build sat unraised behind it, which both disturbs them and silently invalidates the
# run (an unraised window means Chromium throttles rAF, which is what Speedometer
# paces on, so the arm would score garbage while looking like it worked).
#
# Matching the LAUNCHED pid does not work either: on macOS the bundle's binary re-execs,
# so the pid this script holds is not the one the accessibility API sees — the same trap
# ../win/run-boss-arm.ps1 documents for BOSS.exe being a launcher. So: enumerate the
# accessibility processes, resolve each one's executable through ps, and keep the one
# running from this worktree.
boss_window_pid() {
    local pids pid cmd
    pids="$(osascript -e 'tell application "System Events" to get unix id of every process whose name is "BOSS"' 2>/dev/null |
        tr -d ' ' | tr ',' ' ')"
    for pid in $pids; do
        cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
        if [[ "$cmd" == "$APP_BIN"* ]]; then
            echo "$pid"
            return 0
        fi
    done
    return 1
}

# Speedometer 3.1 paces on requestAnimationFrame, which Chromium throttles in a window
# the OS reports hidden — and the fluck viewport is the BOSS window minus tab bar,
# toolbar and sidebar, so a small window can also land under Speedometer's 850x650
# minimum. Every arm must get the SAME window, or the comparison measures window size.
#
# Best-effort by design: a missing tool or a denied permission is a warning, not a
# failed run, because SpeedometerCdp independently refuses a run it detects as
# occluded. But it must never fall back to "resize some other BOSS".
raise_and_maximize() {
    if [[ "$PLATFORM" == "mac" ]]; then
        local pid
        if ! pid="$(boss_window_pid)"; then
            echo "WARNING: no BOSS window found for this worktree's build - not touching any other BOSS." >&2
            echo "         (If your terminal lacks accessibility access, grant it or raise the window by hand.)" >&2
            return 1
        fi
        osascript <<AS >/dev/null 2>&1
tell application "Finder" to set screenBounds to bounds of window of desktop
tell application "System Events"
    tell (first process whose unix id is $pid)
        set frontmost to true
        set position of front window to {0, 0}
        set size of front window to {(item 3 of screenBounds), (item 4 of screenBounds)}
    end tell
end tell
AS
    else
        # Same rule on Linux. `wmctrl -a BOSS` and `xdotool search --name BOSS` both match
        # by TITLE, so both would grab the operator's window; -lp gives the owning pid, so
        # the window can be picked by identity instead.
        local wid
        if command -v wmctrl >/dev/null 2>&1; then
            wid="$(wmctrl -lp 2>/dev/null | while read -r id _ wpid _; do
                [[ "$(ps -p "$wpid" -o command= 2>/dev/null)" == "$APP_BIN"* ]] && { echo "$id"; break; }
            done)"
            if [[ -z "$wid" ]]; then
                echo "WARNING: no BOSS window found for this worktree's build - not touching any other BOSS." >&2
                return 1
            fi
            wmctrl -i -a "$wid" >/dev/null 2>&1 || true
            wmctrl -i -r "$wid" -b add,maximized_vert,maximized_horz >/dev/null 2>&1 || true
        else
            echo "WARNING: wmctrl not found - maximize the benchmark window by hand, or rAF throttling will skew the run." >&2
            return 1
        fi
    fi
    return 0
}

restore_session() {
    if [[ -f "$BACKUP" ]]; then
        cp "$BACKUP" "$LAST_SESSION"
    elif [[ "$HAD_NO_SESSION" == true ]]; then
        # No session existed before this run, so restoring means REMOVING the fixture rather
        # than leaving it behind as the operator's.
        rm -f "$LAST_SESSION"
    fi
}
# An interrupted sweep is the normal case here (Ctrl+C, a failed run), so putting the
# operator's layout back on ANY exit is what makes this self-healing rather than leaving
# their session replaced until someone remembers to restore it.
#
# ORDER MATTERS: kill first, THEN restore. BOSS writes "Last Session" back over this
# shared file as it shuts down, so restoring before the app is gone loses the race and
# leaves the benchmark's single-tab fixture in place as the operator's session. Verified
# the hard way — a probe script that restored first ended up with the fixture saved over
# the real layout, by the dying app, a moment after the restore.
trap 'trap - EXIT INT TERM; stop_dev_boss; restore_session' EXIT INT TERM

mkdir -p "$HERE/$RESULTS"
echo "=== arm '$LABEL'  mode=$RENDERING_MODE  extra='$EXTRA'  repeats=$REPEATS"

for ((i = 1; i <= REPEATS; i++)); do
    stop_dev_boss

    # Force a known layout: ONE full-width browser tab, nothing else. Not tidiness —
    # workspaces live in ~/Documents/BOSS/workspaces and are shared by every BOSS
    # install, dev-mode included, so whatever the operator last had open is what a
    # dev run restores. A restored terminal pane both shrinks the fluck viewport and
    # repaints continuously beside it, measuring the split layout instead of the
    # browser. Rewritten before EVERY launch because BOSS saves "Last Session" back
    # over this file when it exits cleanly. The fixture is shared with the Windows
    # harness — it names no paths, so there is nothing to fork.
    mkdir -p "$(dirname "$LAST_SESSION")"
    cp "$HERE/../win/bench-last-session.json" "$LAST_SESSION"

    echo "[$LABEL] run $i/$REPEATS - launching BOSS"
    # BOSS_LOG_LEVEL is pinned because BOSS_DEV_MODE alone drops the global level to
    # DEBUG (BossLogger.configureFromEnvironment), and the BROWSER category logs on
    # navigation and frame events — i.e. dev mode would be measured doing work a
    # production run never does. BOSS_LOG_LEVEL is checked first, so it wins.
    BOSS_DEV_MODE=true \
    BOSS_LOG_LEVEL=INFO \
    BOSS_BROWSER_REMOTE_DEBUGGING_PORT="$PORT" \
    BOSS_CHROMIUM_EXTRA_SWITCHES="$EXTRA" \
    BOSS_RENDERING_MODE="$RENDERING_MODE" \
        "$APP_BIN" >/dev/null 2>&1 &

    if ! wait_devtools 180; then
        echo "WARNING: [$LABEL] run $i - DevTools port $PORT never came up; skipping" >&2
        continue
    fi
    sleep 5
    raise_and_maximize || true
    # Let the app settle before measuring. BOSS is still loading plugins, starting
    # services and checking for updates for a while after its window appears;
    # benchmarking into that burst measures the startup, not the browser, and it is
    # not what a user sees on an app they have had open. Also gives the layout time
    # to reach its final size.
    sleep "$SETTLE_SECONDS"

    out="$HERE/$RESULTS/$LABEL-$i.json"
    if ! java -cp "$HARNESS_OUT" SpeedometerCdp \
        --name "fluck-$LABEL" --attach "$PORT" \
        --iterations "$ITERATIONS" --out "$out"; then
        echo "WARNING: [$LABEL] run $i failed" >&2
    fi
    stop_dev_boss
done

# Summarize this arm. Median, not mean: a single disturbed run would drag a mean and
# hide itself in it.
#
# One deliberate difference from ../win/run-boss-arm.ps1, so nobody chases it: on an
# EVEN number of usable runs, statistics.median averages the two middle values while
# PowerShell's sorted[floor(n/2)] takes the upper one. Two usable runs of 10 and 30
# report 20 here and 30 there. The standard definition wins; both defaults are odd
# (3 repeats), so it only shows up after a run is excluded.
python3 - "$HERE/$RESULTS" "$LABEL" <<'PY'
import glob, json, statistics, sys
results_dir, label = sys.argv[1], sys.argv[2]
scores = []
for path in sorted(glob.glob(f"{results_dir}/{label}-*.json")):
    with open(path) as fh:
        data = json.load(fh)
    # An occluded run is not a slow run, it is a run of something else: Chromium
    # throttles rAF in a hidden window, which is what Speedometer paces on.
    if data.get("occludedDuringRun"):
        print(f"  (excluded {path}: occluded during run)")
        continue
    scores.append(float(data["score"]))
if scores:
    joined = " / ".join(f"{s:g}" for s in scores)
    print(f"=== arm '{label}': runs={len(scores)} median={statistics.median(scores):g} all={joined}")
else:
    print(f"=== arm '{label}': no usable runs")
PY
