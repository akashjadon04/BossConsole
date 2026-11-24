# Paired fluck-vs-Comet experiment

## Why this exists

The main sweep (`../results-final/`) interleaves browsers round-robin so background
load drift hits all of them equally. The fluck browser could not join that sweep -
it is a tab inside the BOSS host, driven over MCP rather than launched by
`run-all.sh` - so its three runs were collected separately, afterwards.

That made the top of the table the one comparison **not** produced by the controlled
protocol, and the fluck-vs-Comet margin (3.7% in the original sweep) was far smaller
than the drift the protocol exists to defend against. So the ordering was re-measured
properly: Comet and fluck alternated back-to-back, three times, each pair collected
within about two minutes of the other.

## Result

| Pair | Comet | fluck | fluck margin |
|---|---:|---:|---:|
| 1 | 44.7 | 46.5 | +4.0% |
| 2 | 46.1 | 46.95 | +1.8% |
| 3 | 45.4 | 48.3 | +6.4% |
| **median** | **45.4** | **46.95** | **+3.4%** |

fluck was ahead in all three pairs. But the margin (+3.4% median) is the same size as
each browser's own run-to-run spread in this experiment - Comet 44.7-46.1 (3.1%) and
fluck 46.5-48.3 (3.9%). Three paired wins is directionally consistent and nothing more;
under a sign test, 3/3 is p = 0.125.

**Conclusion: fluck and Comet are indistinguishable at this precision, with fluck
ahead in every paired run.** What the data does support firmly is that both sit ~30%
above Chrome (35.5) and Atlas (34.6) - a gap an order of magnitude larger than the
noise, unlike the fluck/Comet ordering.

## Occlusion: the bug this experiment surfaced

The first attempt at pair 1 stalled at 284/580 after five minutes. Cause: the Comet
window was **occluded** - fully covered by another window - and macOS reports such a
window hidden, so Chromium throttles `requestAnimationFrame` in it. Speedometer 3.1
measures with rAF.

An occluded run does not fail. It produces a much lower score and still reports
`valid: true`, which is indistinguishable from a genuinely slow browser. The harness
only ever checked viewport size and Speedometer's own validity flag, never visibility.

`run-chromium.mjs` now:

- passes `--disable-backgrounding-occluded-windows`,
- refuses to start a run whose window reports `document.hidden` (retrying the raise first),
- samples `document.hidden` on every poll and records `occludedDuringRun` in the result.

Every Comet arm above recorded `occludedDuringRun: false`. For the fluck arms,
visibility was checked before each run and completion time was used as the
cross-check - a throttled run cannot finish 580 steps in ~20-40s, where the throttled
attempt managed 11/580 in 40s.

## Ambient load

Recorded per Comet arm: 746%, 688%, 746% - comparable across pairs, so no arm was
measured under a materially different machine.

## Files

- `comet-p1.json`, `comet-p2.json`, `comet-p3.json` - full CDP records with per-suite metrics
- `fluck-paired.json` - the three fluck arms
