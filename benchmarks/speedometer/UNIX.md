# Measuring the fluck browser on macOS and Linux

Companion to [win/WINDOWS.md](win/WINDOWS.md). That document answers "why was the
fluck browser slow on Windows" and its answer was `RenderingMode`, not Chromium
flags. This one exists because **macOS and Linux are a different question, and
running the Windows measurement on them answers it wrongly.**

## Measure the right thing

On Windows, OFF_SCREEN cost throughput: 7.5–11.5 on Speedometer 3.1 against Edge's
24.7 on the same Chromium generation, closed to within ~10% by switching to
HARDWARE_ACCELERATED.

**macOS never had that problem.** It measures 47.9 on OFF_SCREEN, ahead of Chrome,
because the off-screen surface can be shared with the GPU - Windows/D3D11 needs a
real per-frame readback instead. So a Speedometer sweep on macOS is expected to come
out roughly flat between the two modes, and that flat result is not evidence against
HARDWARE. It is evidence that Speedometer is the wrong instrument here.

What OFF_SCREEN costs macOS is **idle power and memory**. BossConsoleLite measured it
over content-matched, clean 94-sample idle windows (`6e637198`):

| | OFF_SCREEN | HARDWARE | Change |
|---|---:|---:|---|
| idle CPU | 0.59 cores | 0.06 cores | **~10x less** |
| RSS | 3095 MB | 1974 MB | **−1.1 GB (−36%)** |
| peak CPU | n/a | n/a | −14% |
| peak RSS | n/a | n/a | −25% |

Linux is the least-measured of the three. It follows Lite's default (`f8d7c708`), and
the arm to run there is the power/memory one too.

So: **two harnesses, and on macOS the second one is the one that matters.**

| Question | Tool |
|---|---|
| Throughput (JS/DOM work per second) | `unix/run-paired-rendering.sh` |
| Idle CPU, memory, process count | `scripts/perf-timeline.py` |

## Interleave, or do not bother

`WINDOWS.md` documents this at length and it is not a theoretical worry. On that
machine a sequential A-then-B comparison read 18.9 against 21.2 and looked like a 10%
regression; re-running the same two builds **alternately** reversed the sign to +10%.
A single unchanged build produced 18.8, 21.1, 21.2, 22.8 and 23.8 in one session - a
27% spread on identical bytes, driven by ambient load.

Consequences, both baked into the scripts:

- Arms alternate. `run-paired-rendering.sh` never groups them.
- The result is the **median of per-pair ratios**, never a ratio of medians - the
  latter averages away a pair whose two arms disagreed. Both the median and the
  win count are printed, because "+5%, won 2 of 3" and "+5%, won 3 of 3" are
  different claims.

Quiet the machine first. Neither script can tell a slow arm from a busy laptop.

## Throughput: paired rendering-mode comparison

```bash
./gradlew :composeApp:createDistributable
cd benchmarks/speedometer/unix
./run-paired-rendering.sh --pairs 3
```

Each arm launches a **fresh** BOSS, because the rendering mode and the Chromium
switches are both read once at engine creation - reusing a process would silently
measure the previous arm. The instance is dev-mode (`~/.boss_debug`), so it cannot
disturb your own `~/.boss` install even while that one is running, and cleanup matches
on the worktree's own binary path so it can never kill your BOSS.

Two things the scripts do that are easy to leave out and invalidate the run:

- **The layout is forced to one full-width browser tab.** Workspaces live in
  `~/Documents/BOSS/workspaces` and are shared by every install, dev-mode included,
  so a dev run restores whatever you last had open. A restored terminal pane both
  shrinks the fluck viewport below Speedometer's 850×650 minimum and repaints
  continuously beside it. Your own session is backed up once and restored on any
  exit, including Ctrl+C.
- **The window is raised and maximized, and it is found by executable path.**
  Speedometer paces on `requestAnimationFrame`, which Chromium throttles in a window
  the OS reports hidden. Your own BOSS is almost certainly running - that is why this
  harness uses dev mode - and it is *also* called "BOSS", so matching the window by
  process name resolves to whichever the OS lists first. That failure is silent and
  doubly bad: it moves your window, and it leaves the benchmark window unraised, so the
  arm scores throttled garbage while appearing to work. On macOS the launched pid is not
  the one the accessibility API sees either (the bundle re-execs), so the window is
  resolved by matching each candidate pid's executable against this worktree's binary.
  If none matches, the script warns and touches nothing. macOS needs accessibility
  permission for your terminal; Linux needs `wmctrl`. `SpeedometerCdp` independently
  refuses a run it detects as occluded.

Note also that **`screencapture` returns an all-black image rather than an error when
Screen Recording permission is missing**, so a screenshot is not a way to check the
browser is compositing - the file gets written and the exit code is 0 either way.

Scoring reuses `win/SpeedometerCdp.java` unchanged - single-file Java on
`java.net.http`, no platform code. Its `--attach` mode is the only way to score a
fluck tab, which lives inside the BOSS process and cannot be spawned like a browser
binary. One implementation reading the score from Speedometer's own DOM on every
platform is what makes the numbers comparable at all.

## Idle power and memory

Start the recorder **before** the launch, keep it running through launch and whatever
exercise you are comparing, then keep recording 25–30s after you stop touching it.
The steady-state summary is taken from that tail.

```bash
# Arm 1
python3 scripts/perf-timeline.py osr /tmp/osr.csv &
BOSS_RENDERING_MODE=OFF_SCREEN <launch BOSS>     # load a page, then leave it alone
touch /tmp/osr.csv.stop

# Arm 2 - the SAME page, the same exercise, the same idle duration
python3 scripts/perf-timeline.py hwa /tmp/hwa.csv &
BOSS_RENDERING_MODE=HARDWARE_ACCELERATED <launch BOSS>
touch /tmp/hwa.csv.stop
```

Compare the `STEADY (last 25s of active)` lines. The two arms must be **content
matched** - same page, loaded and left idle - because a moment of scrolling in one arm
and not the other swamps the difference being measured. Pass a worktree path as the
third argument to isolate one build while your own BOSS keeps running.

**Do not run the recorder from a BossTerm tab inside the BOSS you are measuring.** That
shell is a descendant of the BOSS process, so the recorder, its per-tick `ps`, and
everything you type get summed into the subtree. Smoke-testing this script from a
BossTerm pane turned a machine idling at ~0.4 cores into a 9.64-core sample. Use a
separate terminal app, or point the marker at a different build.

## Results

Nothing recorded on this repo's hardware yet. The macOS and Linux defaults currently
rest on Lite's measurements, cited above and in `JxBrowserConfig.renderingMode`.
Record results here as they land, with the machine, the date, and both arms - a score
with no machine attached is not reusable, as the Windows spread above shows.
