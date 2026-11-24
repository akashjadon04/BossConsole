#!/usr/bin/env node
/**
 * Speedometer 3.1 runner for browsers that expose the Chrome DevTools Protocol on
 * --remote-debugging-port. Verified working with Chrome and Comet.
 *
 * Not every Chromium-based browser qualifies: ChatGPT Atlas wraps its engine in a
 * nested app under Contents/Support, so the flag never reaches the engine and this
 * runner cannot drive it. Use run-screenshot.mjs for those.
 *
 * Zero dependencies: uses Node's built-in global WebSocket (Node >= 21) to speak
 * the Chrome DevTools Protocol directly.
 *
 * Each browser is launched with a throwaway --user-data-dir so no extensions,
 * sync state, or prior profile data can skew the run. The window is raised to
 * the front before the benchmark starts because Speedometer 3.1 measures with
 * requestAnimationFrame by default, and Chromium heavily throttles rAF in
 * occluded or backgrounded windows -- a covered window produces garbage numbers.
 *
 * Usage:
 *   node run-chromium.mjs --name "Google Chrome" \
 *     --binary "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
 *     --port 9222 --iterations 10 --out results/chrome.json
 */

import { spawn, execFile } from "node:child_process";
import { mkdtemp, writeFile, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const RESULT_POLL_MS = 5_000;
const LAUNCH_TIMEOUT_MS = 60_000;
// A 10-iteration Speedometer 3.1 run takes ~20-60s on Apple silicon (the score is
// literally iterations per minute, so ~46 means ~13s of measured work plus load and
// warmup). The cap is therefore very generous slack, sized so a browser that stalls
// outright still terminates -- not an estimate of a normal run.
const RUN_TIMEOUT_MS = 45 * 60_000;

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i += 2) {
        if (!argv[i].startsWith("--"))
            throw new Error(`Expected a --flag at argv position ${i}, got '${argv[i]}'`);
        args[argv[i].slice(2)] = argv[i + 1];
    }
    for (const required of ["name", "binary", "port", "out"]) {
        if (!args[required])
            throw new Error(`Missing required --${required}`);
    }
    args.port = Number(args.port);
    args.iterations = Number(args.iterations ?? 10);
    // An unvalidated value is worse than a crash here: --iterations 1o yields NaN,
    // iterationCount=NaN goes into the URL, Speedometer silently falls back to its
    // default, the run completes, and the record serialises iterationCount as null --
    // a finished-looking result for a workload nobody asked for.
    if (!Number.isInteger(args.iterations) || args.iterations < 1)
        throw new Error(`--iterations must be a positive integer, got '${args.iterations}'`);
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

/** Poll the CDP HTTP endpoint until the browser is accepting connections. */
async function waitForDevTools(port) {
    const deadline = Date.now() + LAUNCH_TIMEOUT_MS;
    while (Date.now() < deadline) {
        try {
            const response = await fetch(`http://127.0.0.1:${port}/json/version`);
            if (response.ok)
                return await response.json();
        } catch {
            // Browser is still starting up; keep polling.
        }
        await sleep(500);
    }
    throw new Error(`DevTools on port ${port} never came up`);
}

/**
 * Find the page target hosting Speedometer.
 *
 * A URL on the command line is only a hint: some Chromium forks (Comet does this
 * intermittently) show their own startup or onboarding page instead and drop it.
 * So if the target does not appear on its own, create it explicitly over CDP.
 */
async function findBenchmarkTarget(port, browserWebSocketDebuggerUrl, url) {
    const isBenchmark = (t) => t.type === "page" && t.url.includes("Speedometer3.1");
    const list = async () => (await fetch(`http://127.0.0.1:${port}/json/list`)).json();

    // Give the command-line URL a short window to show up on its own.
    const hintDeadline = Date.now() + 15_000;
    while (Date.now() < hintDeadline) {
        const page = (await list()).find(isBenchmark);
        if (page?.webSocketDebuggerUrl)
            return page;
        await sleep(500);
    }

    console.log("[info] command-line URL did not open; creating the target over CDP");
    const browser = await CdpSession.connect(browserWebSocketDebuggerUrl);
    try {
        await browser.send("Target.createTarget", { url });
    } finally {
        browser.close();
    }

    const deadline = Date.now() + LAUNCH_TIMEOUT_MS;
    while (Date.now() < deadline) {
        const page = (await list()).find(isBenchmark);
        if (page?.webSocketDebuggerUrl)
            return page;
        await sleep(500);
    }
    throw new Error("Never found a Speedometer3.1 page target");
}

/**
 * Total %CPU of everything on the machine except the browser under test, so a
 * score can be read alongside the load it was measured under. A run taken while
 * something else is eating cores is not comparable to a quiet one.
 *
 * Excludes by .app bundle, not by the main binary path: Chromium's renderer and
 * GPU helpers live under Contents/Frameworks/... and would otherwise be counted
 * as "ambient", inflating the figure by the browser's own work.
 */
async function ambientCpu(bundlePath) {
    const { stdout } = await execFileAsync("/bin/sh", [
        "-c",
        `ps -Ao pcpu=,command= | grep -v -F ${JSON.stringify(bundlePath)} | awk '{s+=$1} END {print s+0}'`,
    ]);
    return Math.round(Number(stdout.trim()) || 0);
}

/** "/Applications/Foo.app/Contents/MacOS/Foo" -> "/Applications/Foo.app" */
function bundleOf(binary) {
    const index = binary.indexOf(".app/");
    return index === -1 ? binary : binary.slice(0, index + 4);
}

/** Minimal CDP session over the built-in WebSocket. */
class CdpSession {
    #socket;
    #nextId = 1;
    #pending = new Map();

    static async connect(webSocketDebuggerUrl) {
        const session = new CdpSession();
        session.#socket = new WebSocket(webSocketDebuggerUrl);
        session.#socket.addEventListener("message", (event) => {
            const message = JSON.parse(event.data);
            const resolver = session.#pending.get(message.id);
            if (!resolver)
                return; // An event, not a command reply -- we don't subscribe to any.
            session.#pending.delete(message.id);
            if (message.error)
                resolver.reject(new Error(`CDP ${message.error.code}: ${message.error.message}`));
            else
                resolver.resolve(message.result);
        });
        await new Promise((resolve, reject) => {
            session.#socket.addEventListener("open", resolve, { once: true });
            session.#socket.addEventListener("error", () => reject(new Error("CDP socket failed")), { once: true });
        });
        return session;
    }

    send(method, params = {}) {
        const id = this.#nextId++;
        return new Promise((resolve, reject) => {
            this.#pending.set(id, { resolve, reject });
            this.#socket.send(JSON.stringify({ id, method, params }));
        });
    }

    /** Evaluate an expression and return its JSON value. */
    async evaluate(expression) {
        const { result, exceptionDetails } = await this.send("Runtime.evaluate", {
            expression,
            returnByValue: true,
            awaitPromise: true,
        });
        if (exceptionDetails)
            throw new Error(`Evaluate threw: ${exceptionDetails.text} ${exceptionDetails.exception?.description ?? ""}`);
        return result.value;
    }

    close() {
        this.#socket.close();
    }
}

/**
 * Read the score straight out of the DOM that Speedometer itself renders:
 * #result-number and #confidence-number in the #summary section.
 * Also pulls per-suite scores off the live benchmark client when it exposes them.
 */
const EXTRACT_SCORE = `(() => {
    const scoreEl = document.getElementById("result-number");
    const confidenceEl = document.getElementById("confidence-number");
    const score = scoreEl?.textContent?.trim() ?? "";
    if (!score)
        return null;
    const summary = document.getElementById("summary");
    let suites = null;
    try {
        const metrics = globalThis.benchmarkClient?._metrics;
        if (metrics) {
            suites = {};
            for (const [key, metric] of Object.entries(metrics)) {
                if (metric && typeof metric.mean === "number")
                    suites[key] = { mean: metric.mean, delta: metric.delta ?? null, unit: metric.unit ?? null };
            }
        }
    } catch {
        suites = null;
    }
    return {
        score: Number(score),
        scoreText: score,
        confidenceText: confidenceEl?.textContent?.trim() ?? null,
        // Speedometer marks a run "invalid" (class not "valid") when the viewport
        // was too small or the run was disturbed. Recorded so we never silently
        // report a tainted score.
        valid: summary?.classList?.contains("valid") ?? null,
        suites,
    };
})()`;

const PROGRESS = `(() => {
    const label = document.getElementById("info-label")?.textContent?.trim() ?? "";
    const progress = document.getElementById("info-progress")?.textContent?.trim() ?? "";
    return [label, progress].filter(Boolean).join(" ") || document.body?.className || "";
})()`;

/** Raise the browser window so rAF is not throttled by occlusion. */
async function raiseWindow(appName) {
    try {
        await execFileAsync("osascript", ["-e", `tell application "${appName}" to activate`]);
    } catch (error) {
        console.warn(`[warn] could not activate ${appName}: ${error.message}`);
    }
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    const profileDir = await mkdtemp(join(tmpdir(), "speedometer-profile-"));
    const url = benchmarkUrl(args.iterations);

    console.log(`[${args.name}] launching with fresh profile ${profileDir}`);
    const child = spawn(
        args.binary,
        [
            `--remote-debugging-port=${args.port}`,
            `--user-data-dir=${profileDir}`,
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
            // macOS marks a fully-covered window occluded, and Chromium then
            // throttles requestAnimationFrame in it. Speedometer 3.1 measures with
            // rAF, so an occluded window yields a crawling run that still reports
            // valid:true -- a slow-looking browser that is really just covered up.
            "--disable-backgrounding-occluded-windows",
            "--window-size=1280,900",
            "--window-position=0,0",
            url,
        ],
        { stdio: "ignore", detached: false }
    );
    child.on("error", (error) => {
        console.error(`[${args.name}] launch failed: ${error.message}`);
        process.exit(1);
    });

    let session;
    try {
        const version = await waitForDevTools(args.port);
        console.log(`[${args.name}] ${version["User-Agent"]}`);
        const target = await findBenchmarkTarget(args.port, version.webSocketDebuggerUrl, url);
        session = await CdpSession.connect(target.webSocketDebuggerUrl);

        // Give the page a moment to lay out, then raise the window for the whole run.
        await sleep(2_000);
        await raiseWindow(args.name);

        const viewport = await session.evaluate(
            `({ w: innerWidth, h: innerHeight, dpr: devicePixelRatio })`
        );
        console.log(`[${args.name}] viewport ${viewport.w}x${viewport.h} @${viewport.dpr}x`);
        if (viewport.w < 850 || viewport.h < 650)
            console.warn(`[${args.name}] WARNING viewport under Speedometer's 850x650 minimum`);

        // Refuse to measure a window the OS considers hidden. Retry the raise a few
        // times first -- a window can legitimately still be settling right after launch.
        let visible = false;
        for (let attempt = 0; attempt < 5 && !visible; attempt++) {
            visible = !(await session.evaluate(`document.hidden`));
            if (visible)
                break;
            console.warn(`[${args.name}] window is occluded/hidden; re-raising (attempt ${attempt + 1})`);
            await raiseWindow(args.name);
            await sleep(2_000);
        }
        if (!visible) {
            throw new Error(
                `${args.name} window stayed hidden (document.hidden === true). rAF is throttled ` +
                    `while occluded, so any score from this run would be meaningless. Un-cover the ` +
                    `window (a full-screen app on another Space will do it) and re-run.`
            );
        }

        const deadline = Date.now() + RUN_TIMEOUT_MS;
        const ambientSamples = [];
        // Occlusion can start mid-run too, so keep watching rather than checking once.
        let everHidden = false;
        let result = null;
        while (Date.now() < deadline) {
            result = await session.evaluate(EXTRACT_SCORE);
            if (result)
                break;
            const progress = await session.evaluate(PROGRESS);
            const ambient = await ambientCpu(bundleOf(args.binary));
            ambientSamples.push(ambient);
            if (await session.evaluate(`document.hidden`)) {
                everHidden = true;
                console.warn(`[${args.name}] window became hidden mid-run; score will be flagged`);
            }
            const elapsed = Math.round((RUN_TIMEOUT_MS - (deadline - Date.now())) / 1000);
            console.log(`[${args.name}] ${elapsed}s ${progress} ambient=${ambient}%`);
            await sleep(RESULT_POLL_MS);
        }
        if (!result)
            throw new Error(`Run did not finish within ${RUN_TIMEOUT_MS / 60_000} minutes`);
        if (everHidden)
            console.warn(`[${args.name}] WARNING this run was occluded at some point; do not compare it`);

        const record = {
            // false means the window was visible for every sample. A true here
            // invalidates the score: rAF was throttled for part of the run.
            occludedDuringRun: everHidden,
            // Competing CPU load during the run, excluding this browser. Reported so
            // a depressed score can be attributed rather than mistaken for a slow engine.
            ambientCpuPercent: ambientSamples.length
                ? {
                      samples: ambientSamples,
                      median: ambientSamples.slice().sort((a, b) => a - b)[Math.floor(ambientSamples.length / 2)],
                  }
                : null,
            browser: args.name,
            binary: args.binary,
            userAgent: version["User-Agent"],
            browserVersion: version.Browser,
            benchmark: "Speedometer 3.1",
            url,
            iterationCount: args.iterations,
            viewport,
            recordedAt: new Date().toISOString(),
            ...result,
        };
        await mkdir(dirname(args.out), { recursive: true });
        await writeFile(args.out, `${JSON.stringify(record, null, 2)}\n`);
        console.log(`[${args.name}] SCORE ${result.scoreText} ${result.confidenceText ?? ""} (valid=${result.valid})`);
        console.log(`[${args.name}] wrote ${args.out}`);
    } finally {
        session?.close();
        // Shut the browser down and *verify* it is gone. A survivor from a failed run
        // silently steals cores from every later run in the sweep, which reads as the
        // next browser being slow -- the exact way a benchmark sweep produces
        // confident nonsense.
        child.kill("SIGTERM");
        await sleep(2_000);
        for (let attempt = 0; attempt < 10; attempt++) {
            const { stdout } = await execFileAsync("/bin/sh", [
                "-c",
                `pgrep -f ${JSON.stringify(profileDir)} | wc -l`,
            ]);
            const alive = Number(stdout.trim()) || 0;
            if (alive === 0)
                break;
            console.log(`[${args.name}] ${alive} process(es) still alive; killing`);
            await execFileAsync("/bin/sh", ["-c", `pkill -9 -f ${JSON.stringify(profileDir)} || true`]);
            await sleep(1_000);
        }
        await rm(profileDir, { recursive: true, force: true }).catch(() => {});
    }
}

await main();
