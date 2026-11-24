#!/usr/bin/env node
/**
 * Speedometer 3.1 runner for browsers driven over W3C WebDriver:
 * Firefox (geckodriver) and Safari (safaridriver).
 *
 * Zero dependencies: WebDriver is plain HTTP + JSON, so global fetch is enough.
 *
 * Safari needs `sudo safaridriver --enable` once before this will work.
 *
 * Usage:
 *   node run-webdriver.mjs --name Firefox --driver geckodriver --port 4444 \
 *     --iterations 10 --out results/firefox.json
 */

import { spawn, execFile } from "node:child_process";
import { writeFile, mkdir } from "node:fs/promises";
import { dirname } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const RESULT_POLL_MS = 5_000;
const DRIVER_TIMEOUT_MS = 30_000;
const RUN_TIMEOUT_MS = 45 * 60_000;

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i += 2) {
        if (!argv[i].startsWith("--"))
            throw new Error(`Expected a --flag at argv position ${i}, got '${argv[i]}'`);
        args[argv[i].slice(2)] = argv[i + 1];
    }
    for (const required of ["name", "driver", "port", "out"]) {
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

class WebDriverSession {
    #base;
    #id;

    constructor(base, id) {
        this.#base = base;
        this.#id = id;
    }

    static async #request(url, method, body) {
        const response = await fetch(url, {
            method,
            headers: { "Content-Type": "application/json" },
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        const text = await response.text();
        let json;
        try {
            json = JSON.parse(text);
        } catch {
            throw new Error(`Non-JSON WebDriver reply (${response.status}): ${text.slice(0, 400)}`);
        }
        if (!response.ok || json.value?.error)
            throw new Error(`WebDriver ${json.value?.error ?? response.status}: ${json.value?.message ?? text.slice(0, 400)}`);
        return json.value;
    }

    static async create(port, capabilities) {
        const base = `http://127.0.0.1:${port}`;
        const value = await WebDriverSession.#request(`${base}/session`, "POST", {
            capabilities: { alwaysMatch: capabilities },
        });
        return { session: new WebDriverSession(base, value.sessionId), capabilities: value.capabilities };
    }

    navigate(url) {
        return WebDriverSession.#request(`${this.#base}/session/${this.#id}/url`, "POST", { url });
    }

    setWindowRect(rect) {
        return WebDriverSession.#request(`${this.#base}/session/${this.#id}/window/rect`, "POST", rect);
    }

    /** Run a synchronous script and return its JSON value. */
    execute(script, args = []) {
        return WebDriverSession.#request(`${this.#base}/session/${this.#id}/execute/sync`, "POST", { script, args });
    }

    quit() {
        return WebDriverSession.#request(`${this.#base}/session/${this.#id}`, "DELETE");
    }
}

/**
 * Same DOM contract as the Chromium runner: Speedometer renders the final score
 * into #result-number and the +/- interval into #confidence-number.
 */
const EXTRACT_SCORE = `
    var scoreEl = document.getElementById("result-number");
    var confidenceEl = document.getElementById("confidence-number");
    var score = scoreEl && scoreEl.textContent ? scoreEl.textContent.trim() : "";
    if (!score)
        return null;
    var summary = document.getElementById("summary");
    var suites = null;
    try {
        var metrics = window.benchmarkClient && window.benchmarkClient._metrics;
        if (metrics) {
            suites = {};
            for (var key in metrics) {
                var metric = metrics[key];
                if (metric && typeof metric.mean === "number")
                    suites[key] = { mean: metric.mean, delta: metric.delta || null, unit: metric.unit || null };
            }
        }
    } catch (e) {
        suites = null;
    }
    return {
        score: Number(score),
        scoreText: score,
        confidenceText: confidenceEl && confidenceEl.textContent ? confidenceEl.textContent.trim() : null,
        valid: summary && summary.classList ? summary.classList.contains("valid") : null,
        suites: suites
    };
`;

const PROGRESS = `
    var label = document.getElementById("info-label");
    var progress = document.getElementById("info-progress");
    return [label ? label.textContent.trim() : "", progress ? progress.textContent.trim() : ""].filter(Boolean).join(" ");
`;

async function raiseWindow(appName) {
    try {
        await execFileAsync("osascript", ["-e", `tell application "${appName}" to activate`]);
    } catch (error) {
        console.warn(`[warn] could not activate ${appName}: ${error.message}`);
    }
}

/** geckodriver/safaridriver both expose /status once ready. */
async function waitForDriver(port) {
    const deadline = Date.now() + DRIVER_TIMEOUT_MS;
    while (Date.now() < deadline) {
        try {
            const response = await fetch(`http://127.0.0.1:${port}/status`);
            if (response.ok) {
                const { value } = await response.json();
                if (value?.ready !== false)
                    return value;
            }
        } catch {
            // Driver still binding its port.
        }
        await sleep(400);
    }
    throw new Error(`WebDriver on port ${port} never reported ready`);
}

function capabilitiesFor(name) {
    if (name.toLowerCase().includes("firefox")) {
        return {
            browserName: "firefox",
            // A fresh profile per run: no extensions or prior state skewing results.
            "moz:firefoxOptions": { args: [] },
        };
    }
    return { browserName: "safari" };
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    const url = benchmarkUrl(args.iterations);

    console.log(`[${args.name}] starting ${args.driver} on port ${args.port}`);
    const driver = spawn(args.driver, ["--port", String(args.port)], { stdio: "ignore" });
    driver.on("error", (error) => {
        console.error(`[${args.name}] could not start ${args.driver}: ${error.message}`);
        process.exit(1);
    });

    let session;
    try {
        await waitForDriver(args.port);
        const created = await WebDriverSession.create(args.port, capabilitiesFor(args.name));
        session = created.session;
        console.log(`[${args.name}] session up: ${created.capabilities.browserName} ${created.capabilities.browserVersion}`);

        // Speedometer refuses to certify runs under 850x650; give it plenty of room.
        try {
            await session.setWindowRect({ x: 0, y: 0, width: 1280, height: 900 });
        } catch (error) {
            console.warn(`[${args.name}] could not set window rect: ${error.message}`);
        }

        await session.navigate(url);
        await sleep(2_000);
        await raiseWindow(args.name);

        const viewport = await session.execute(
            `return { w: window.innerWidth, h: window.innerHeight, dpr: window.devicePixelRatio };`
        );
        console.log(`[${args.name}] viewport ${viewport.w}x${viewport.h} @${viewport.dpr}x`);
        if (viewport.w < 850 || viewport.h < 650)
            console.warn(`[${args.name}] WARNING viewport under Speedometer's 850x650 minimum`);

        const deadline = Date.now() + RUN_TIMEOUT_MS;
        let result = null;
        while (Date.now() < deadline) {
            result = await session.execute(EXTRACT_SCORE);
            if (result)
                break;
            const progress = await session.execute(PROGRESS);
            const elapsed = Math.round((RUN_TIMEOUT_MS - (deadline - Date.now())) / 1000);
            console.log(`[${args.name}] ${elapsed}s ${progress}`);
            await sleep(RESULT_POLL_MS);
        }
        if (!result)
            throw new Error(`Run did not finish within ${RUN_TIMEOUT_MS / 60_000} minutes`);

        const record = {
            browser: args.name,
            driver: args.driver,
            browserVersion: created.capabilities.browserVersion ?? null,
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
        try {
            await session?.quit();
        } catch {
            // Session may already be gone.
        }
        driver.kill("SIGTERM");
        await sleep(1_000);
        if (!driver.killed)
            driver.kill("SIGKILL");
    }
}

await main();
