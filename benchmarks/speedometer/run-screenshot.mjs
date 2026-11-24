#!/usr/bin/env node
/**
 * Speedometer 3.1 runner for browsers that expose no automation endpoint:
 * Safari and Dia (WebKit, remote automation off by default) and ChatGPT Atlas
 * (its engine lives in a nested app under Contents/Support, so a
 * --remote-debugging-port passed to the outer binary never reaches it).
 *
 * Since we cannot evaluate JS in these browsers, the score is read off a
 * screenshot of the result screen instead. Run completion is detected from the
 * browser's own CPU usage: Speedometer pins a core for the whole run, so the
 * transition from busy back to idle marks the end of the benchmark.
 *
 * The screenshot doubles as the audit trail -- it shows Speedometer's own
 * "valid"/invalid banner, so a run degraded by a too-small viewport cannot be
 * silently reported as a good number.
 *
 * Usage:
 *   node run-screenshot.mjs --name Safari --iterations 10 \
 *     --out results/safari.json --shot results/safari.png
 */

import { execFile } from "node:child_process";
import { writeFile, mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const SAMPLE_MS = 3_000;
// Thresholds are measured RELATIVE to a baseline sampled before the browser
// launches, not as absolute CPU%. On macOS a `ps` match like "com.apple.WebKit"
// catches every app embedding WKWebView (chat apps, mail, Slack), so an absolute
// "idle means < 15%" rule never fires when some unrelated app is busy -- the run
// then spins until its timeout instead of capturing a finished result.
const BUSY_MARGIN = 40;
const IDLE_MARGIN = 25;
// Consecutive idle samples required before declaring the run finished, so a brief
// dip between suites is not mistaken for completion.
const IDLE_SAMPLES_TO_FINISH = 5;
const START_TIMEOUT_MS = 90_000;
// Hard cap on a single run. A 10-iteration Speedometer 3.1 run takes well under a
// minute on Apple silicon, so if the CPU signal is unusable we still capture the
// screen and let the screenshot show whether the run finished.
const MAX_RUN_MS = 180_000;

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i += 2) {
        if (!argv[i].startsWith("--"))
            throw new Error(`Expected a --flag at argv position ${i}, got '${argv[i]}'`);
        args[argv[i].slice(2)] = argv[i + 1];
    }
    for (const required of ["name", "out", "shot"]) {
        if (!args[required])
            throw new Error(`Missing required --${required}`);
    }
    args.iterations = Number(args.iterations ?? 10);
    // An unvalidated value is worse than a crash here: --iterations 1o yields NaN,
    // iterationCount=NaN goes into the URL, Speedometer silently falls back to its
    // default, the run completes, and the record serialises iterationCount as null --
    // a finished-looking result for a workload nobody asked for.
    if (!Number.isInteger(args.iterations) || args.iterations < 1)
        throw new Error(`--iterations must be a positive integer, got '${args.iterations}'`);
    // Which processes count toward the CPU signal; defaults to the app name.
    args.match = args.match ?? args.name;
    // `open -a <name>` resolves through LaunchServices, which does not know about
    // an app that was just copied into /Applications. Prefer an explicit bundle
    // path when one is given.
    args.app = args.app ?? args.name;
    return args;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function benchmarkUrl(iterations) {
    const params = new URLSearchParams({
        startAutomatically: "1",
        iterationCount: String(iterations),
    });
    return `https://browserbench.org/Speedometer3.1/?${params}`;
}

/** Sum %CPU over every process whose command line mentions `match`. */
async function browserCpu(match) {
    const { stdout } = await execFileAsync("/bin/sh", [
        "-c",
        `ps -Ao pcpu=,command= | grep -F ${JSON.stringify(match)} | grep -v grep | awk '{s+=$1} END {print s+0}'`,
    ]);
    return Number(stdout.trim()) || 0;
}

async function activate(appName) {
    try {
        await execFileAsync("osascript", ["-e", `tell application "${appName}" to activate`]);
    } catch (error) {
        console.warn(`[warn] could not activate ${appName}: ${error.message}`);
    }
}

/**
 * Safari understands `bounds` natively, so we can guarantee it clears
 * Speedometer's 850x650 minimum. Other apps are left at their default size and
 * verified visually from the screenshot instead.
 */
async function trySetSafariBounds(appName) {
    if (appName !== "Safari")
        return;
    try {
        await execFileAsync("osascript", [
            "-e",
            `tell application "Safari" to set bounds of front window to {0, 0, 1280, 900}`,
        ]);
    } catch (error) {
        console.warn(`[warn] could not size Safari window: ${error.message}`);
    }
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    const url = benchmarkUrl(args.iterations);

    // Baseline BEFORE launching, so unrelated processes matching the same pattern
    // (other WKWebView apps, for Safari) are subtracted out rather than mistaken
    // for the benchmark still running.
    const baseline = await browserCpu(args.match);
    console.log(`[${args.name}] baseline cpu for '${args.match}' = ${baseline.toFixed(0)}%`);

    console.log(`[${args.name}] opening ${url}`);
    await execFileAsync("open", ["-a", args.app, url]);
    await sleep(6_000);
    await trySetSafariBounds(args.name);
    await activate(args.name);

    // Phase 1: wait for the benchmark to actually get going.
    const startDeadline = Date.now() + START_TIMEOUT_MS;
    let sawBusy = false;
    while (Date.now() < startDeadline) {
        const cpu = await browserCpu(args.match);
        console.log(`[${args.name}] waiting for start, cpu=${cpu.toFixed(0)}% (baseline ${baseline.toFixed(0)}%)`);
        if (cpu > baseline + BUSY_MARGIN) {
            sawBusy = true;
            break;
        }
        await sleep(SAMPLE_MS);
    }
    if (!sawBusy)
        console.warn(`[${args.name}] never saw a busy CPU phase; the run may not have auto-started`);

    // Phase 2: run until CPU settles back toward the baseline, or the cap expires.
    const runDeadline = Date.now() + MAX_RUN_MS;
    let idleStreak = 0;
    let cappedOut = true;
    const startedAt = Date.now();
    while (Date.now() < runDeadline) {
        await sleep(SAMPLE_MS);
        const cpu = await browserCpu(args.match);
        idleStreak = cpu < baseline + IDLE_MARGIN ? idleStreak + 1 : 0;
        const elapsed = Math.round((Date.now() - startedAt) / 1000);
        console.log(`[${args.name}] ${elapsed}s cpu=${cpu.toFixed(0)}% idleStreak=${idleStreak}`);
        if (idleStreak >= IDLE_SAMPLES_TO_FINISH) {
            cappedOut = false;
            break;
        }
    }
    if (cappedOut)
        console.warn(`[${args.name}] hit the ${MAX_RUN_MS / 1000}s cap; check the screenshot to confirm the run finished`);

    // Let the result screen paint before capturing it.
    await activate(args.name);
    await sleep(2_000);
    await mkdir(dirname(args.shot), { recursive: true });
    await execFileAsync("screencapture", ["-x", "-t", "png", args.shot]);
    console.log(`[${args.name}] captured ${args.shot}`);

    const record = {
        browser: args.name,
        benchmark: "Speedometer 3.1",
        url,
        iterationCount: args.iterations,
        method: "screenshot",
        // Filled in by reading `screenshot`; this runner cannot execute JS in
        // these browsers, so the number is transcribed from Speedometer's own UI.
        score: null,
        confidenceText: null,
        screenshot: args.shot,
        recordedAt: new Date().toISOString(),
        elapsedSeconds: Math.round((Date.now() - startedAt) / 1000),
    };
    await mkdir(dirname(args.out), { recursive: true });
    await writeFile(args.out, `${JSON.stringify(record, null, 2)}\n`);
    console.log(`[${args.name}] wrote ${args.out} (score to be read from screenshot)`);

    // Leave the browser closed so the next run starts from a clean, idle machine.
    try {
        await execFileAsync("osascript", ["-e", `tell application "${args.name}" to quit`]);
    } catch {
        // Some apps refuse a scripted quit; not fatal.
    }
}

await main();
