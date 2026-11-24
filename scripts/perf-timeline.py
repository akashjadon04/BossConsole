#!/usr/bin/env python3
"""
Continuous process-tree performance recorder for BOSS A/B measurement, macOS and Linux.

Ported from BossConsoleLite's scripts/mac-perf-timeline.py (6e637198), which is what
produced the macOS numbers behind defaulting to HARDWARE_ACCELERATED: idle CPU 0.59 ->
0.06 cores, RSS 3095 -> 1974 MB. **This is the arm that matters on macOS.** Speedometer
measures throughput, and macOS never had a throughput problem with OFF_SCREEN — it
scores 47.9 there, ahead of Chrome. What OFF_SCREEN costs macOS is idle power and
memory, which is what this records and Speedometer cannot see.

Samples the BOSS process subtree once per second — the app JVM plus ALL descendants (the
out-of-process JxBrowser/Chromium helpers) — and records process count, total resident
memory, and CPU in CORES, computed from cumulative cputime deltas between ticks so it is
instantaneous rather than a lifetime average.

Two-scan design, kept from Lite: discovering roots needs each process's full command
line, which is expensive, so it runs rarely; the per-tick scan omits the command field
entirely and stays cheap enough to hold 1 Hz while a build is running. Roots are
re-discovered every 10 ticks so a relaunch mid-recording is picked up.

Method, and the order is the point: start the recorder BEFORE the launch, keep it
running through launch and whatever exercise you are comparing, then keep recording
25-30s after you stop touching it. The steady-state summary is taken from that tail —
the two arms have to be compared on matched IDLE windows, because a moment of scrolling
in one arm and not the other swamps the difference being measured.

DO NOT RUN THIS FROM A TERMINAL INSIDE THE BOSS YOU ARE MEASURING. A BossTerm tab's
shell is a descendant of the BOSS process, so the recorder, its per-tick `ps`, and every
command you type land inside the subtree being summed. Observed while smoke-testing this
script from a BossTerm pane: a machine otherwise idling at ~0.4 cores produced a 9.64-core
sample and a 170 MB RSS bump, all of it the recorder and its shell. Use a separate
terminal app, or pass a `marker` naming a worktree build other than the host.

Usage:
    python3 scripts/perf-timeline.py <label> <csv_path> [marker]

`marker` is matched as a substring of each process's command line, and defaults to
BOSS.app's binary. Pass a worktree path to measure one specific build while your own
BOSS keeps running:

    python3 scripts/perf-timeline.py hwa /tmp/hwa.csv ~/Development/Boss/.worktrees/x

Stop it by creating the sentinel file:  touch <csv_path>.stop
"""
import os
import platform
import subprocess
import sys
import time

LABEL = sys.argv[1] if len(sys.argv) > 1 else "run"
CSV = sys.argv[2] if len(sys.argv) > 2 else f"/tmp/boss-perf-{LABEL}.csv"
STOP = CSV + ".stop"
# Lite matched on `boss.lite.mode=true`, a system property its Gradle run task sets. That
# has no equivalent for a PACKAGED BOSS, which is what the benchmark harness launches, so
# the default marker is the app binary path and a worktree path is the way to isolate one
# build. Matching the main class instead would catch every BOSS on the machine at once.
#
# Platform-derived, because the bundle layouts differ and this tool is documented for both:
# macOS runs BOSS.app/Contents/MacOS/BOSS, Linux runs <dist>/BOSS/bin/BOSS. A hardcoded macOS
# default matched nothing on Linux, which surfaced as "app never detected" against a docstring
# and a UNIX.md example that both omit the argument.
_DEFAULT_MARKERS = {"Darwin": "BOSS.app/Contents/MacOS/BOSS", "Linux": "/BOSS/bin/BOSS"}
MARKER = sys.argv[3] if len(sys.argv) > 3 else _DEFAULT_MARKERS.get(platform.system(), "BOSS")

STEADY_TAIL_SECONDS = 25


def parse_cputime(s):
    """ps cputime formats: SS.ss | MM:SS.ss | HH:MM:SS | DD-HH:MM:SS."""
    s = s.strip()
    days = 0
    if "-" in s:
        d, s = s.split("-", 1)
        days = int(d)
    sec = 0.0
    for part in s.split(":"):
        sec = sec * 60 + float(part)
    return days * 86400 + sec


def discover_roots():
    """Expensive scan (full command line) for the app JVM PID(s). Run rarely."""
    out = subprocess.run(
        ["ps", "-axww", "-o", "pid=,command="], capture_output=True, text=True
    ).stdout
    roots = set()
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            pid_s, cmd = line.split(None, 1)
        except ValueError:
            continue
        if MARKER in cmd:
            roots.add(int(pid_s))
    return roots


def cheap_tree(roots):
    """Cheap per-tick scan summing the subtree of `roots`.

    Returns (nprocs, rss_kb, cpu_seconds, live_roots). Walking ppid ourselves rather
    than asking ps for a process group: Chromium's helpers are not all in one group,
    and a group query would miss the ones that matter most for this measurement.
    """
    out = subprocess.run(
        ["ps", "-axo", "pid=,ppid=,rss=,cputime="], capture_output=True, text=True
    ).stdout
    procs, children = {}, {}
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            pid_s, ppid_s, rss_s, cput_s = line.split(None, 3)
            pid, ppid, rss, cpu = int(pid_s), int(ppid_s), int(rss_s), parse_cputime(cput_s)
        except ValueError:
            continue
        procs[pid] = (ppid, rss, cpu)
        children.setdefault(ppid, []).append(pid)

    live_roots = {r for r in roots if r in procs}
    seen, stack = set(), list(live_roots)
    while stack:
        pid = stack.pop()
        if pid in seen:
            continue
        seen.add(pid)
        stack.extend(children.get(pid, []))
    rss_kb = sum(procs[p][1] for p in seen)
    cpu_s = sum(procs[p][2] for p in seen)
    return len(seen), rss_kb, cpu_s, live_roots


def summarize(rows):
    active = [r for r in rows if r[1] > 0]
    print(f"\n=== SUMMARY [{LABEL}] ===", flush=True)
    if not active:
        print("no active samples (app never detected - check the marker)", flush=True)
        return
    procs = [r[1] for r in active]
    rss = [r[2] for r in active]
    cpu = [r[3] for r in active]
    tail = [r for r in active if r[0] >= active[-1][0] - STEADY_TAIL_SECONDS]
    rss_t = [r[2] for r in tail]
    cpu_t = [r[3] for r in tail]
    print(f"active samples : {len(active)}  ({active[0][0]:.0f}s -> {active[-1][0]:.0f}s)", flush=True)
    print(f"procs   min/mean/max : {min(procs)} / {sum(procs) / len(procs):.1f} / {max(procs)}", flush=True)
    print(f"rss_MB  min/mean/max : {min(rss):.0f} / {sum(rss) / len(rss):.0f} / {max(rss):.0f}", flush=True)
    print(f"cpu_cores min/mean/max: {min(cpu):.2f} / {sum(cpu) / len(cpu):.2f} / {max(cpu):.2f}", flush=True)
    # The line to compare between arms. Quoting a whole-run mean instead would fold the
    # launch burst into it, and the launch is the same work in both modes.
    print(
        f"STEADY (last {STEADY_TAIL_SECONDS}s of active): "
        f"rss_mean={sum(rss_t) / len(rss_t):.0f}MB  cpu_mean={sum(cpu_t) / len(cpu_t):.2f} cores",
        flush=True,
    )


def main():
    if os.path.exists(STOP):
        os.remove(STOP)
    start = time.time()
    last_t, last_cpu = None, None
    rows, roots, tick = [], set(), 0
    print(f"[{LABEL}] recording -> {CSV}  (marker: {MARKER})  (stop: touch {STOP})", flush=True)
    with open(CSV, "w") as fh:
        fh.write("epoch,elapsed_s,nprocs,rss_mb,cpu_cores\n")
        while not os.path.exists(STOP):
            now = time.time()
            if not roots or tick % 10 == 0:
                roots |= discover_roots()
            n, rss_kb, cpu_s, roots = cheap_tree(roots)
            tick += 1
            cores = 0.0
            if n > 0 and last_t is not None and last_cpu is not None:
                dt = now - last_t
                if dt > 0:
                    # Clamped at 0: a process leaving the tree drops cumulative cputime,
                    # which would otherwise read as negative CPU for that tick.
                    cores = max(0.0, (cpu_s - last_cpu) / dt)
            last_t = now
            last_cpu = cpu_s if n > 0 else None
            rss_mb = rss_kb / 1024.0
            fh.write(f"{now:.1f},{now - start:.1f},{n},{rss_mb:.1f},{cores:.2f}\n")
            fh.flush()
            rows.append((now - start, n, rss_mb, cores))
            print(
                f"[{LABEL}] t={now - start:5.0f}s  procs={n:2d}  rss={rss_mb:8.1f}MB  cpu={cores:5.2f} cores",
                flush=True,
            )
            time.sleep(1.0)

    summarize(rows)
    if os.path.exists(STOP):
        os.remove(STOP)


if __name__ == "__main__":
    main()
