#!/usr/bin/env node
/**
 * Runs the find-key probe against a fake DOM.
 *
 * BrowserFindKeyProbe's script decides whether BOSS's find bar opens or the PAGE keeps the find
 * chord, and it lives as JavaScript inside a Kotlin string - so the Kotlin suite can only grep it.
 * What matters here is not the text but the behaviour, and one behaviour in particular:
 *
 *   A page that calls `preventDefault()` AND `stopPropagation()` must still be reported as having
 *   handled the key.
 *
 * That is what Google Sheets, Docs and Notion do, and it is the case the obvious implementation
 * gets wrong. Reading `defaultPrevented` from a bubble-phase listener looks equivalent and is not:
 * a stopped event never reaches the bubble phase, so the page's own find would be reported as
 * "free" and BOSS would open its bar over the top. Deferring with `setTimeout(..., 0)` from the
 * capture phase is what makes it correct, and only executing the script can tell the two apart.
 *
 * Same technique and directory as test-browser-collector.js, for the same reason.
 *
 * Usage: node scripts/test/test-find-key-probe.js
 */

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const repoRoot = path.resolve(__dirname, '../..');
const probeKt = path.join(
  repoRoot,
  'composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserFindKeyProbe.kt',
);

let failures = 0;
function eq(name, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (ok) {
    console.log(`  ok   ${name}`);
  } else {
    failures++;
    console.log(`  FAIL ${name} -> got ${JSON.stringify(actual)}, want ${JSON.stringify(expected)}`);
  }
}

// ---------------------------------------------------------------------------
// Extract the probe source out of the Kotlin string, resolving $CONSTANTS.
// ---------------------------------------------------------------------------
function loadProbe() {
  const src = fs.readFileSync(probeKt, 'utf8');
  const consts = {};
  for (const m of src.matchAll(/const val (\w+)\s*(?::\s*\w+)?\s*=\s*"([^"\n]*)"/g)) {
    consts[m[1]] = m[2];
  }
  const body = src.split('"""')[1];
  const lines = body.split('\n').slice(1);
  const indent = Math.min(
    ...lines.filter((l) => l.trim()).map((l) => l.length - l.trimStart().length),
  );
  let js = lines.map((l) => (l.trim() ? l.slice(indent) : '')).join('\n');
  for (const k of Object.keys(consts).sort((a, b) => b.length - a.length)) {
    js = js.split('$' + k).join(consts[k]);
  }
  const unresolved = js.split('\n').filter((l) => l.includes('$'));
  if (unresolved.length) {
    throw new Error(`unresolved interpolation in probe source: ${unresolved[0]}`);
  }
  return { js, consts };
}

const { js: PROBE, consts } = loadProbe();

// ---------------------------------------------------------------------------
// A DOM event model just large enough: a three-node path, both phases, and the
// three ways a page can interfere.
// ---------------------------------------------------------------------------
function makeEnv(platform) {
  const listeners = new Map();
  const reg = (node) => {
    if (!listeners.has(node)) listeners.set(node, { capture: [], bubble: [] });
    return listeners.get(node);
  };
  const timers = [];
  const node = (name) => {
    const n = { name };
    n.addEventListener = (_type, fn, capture) => reg(n)[capture ? 'capture' : 'bubble'].push(fn);
    return n;
  };
  const target = node('target');
  const document = node('document');
  const win = node('window');
  win.navigator = { platform };
  win.document = document;
  win.window = win;
  win.setTimeout = (fn) => timers.push(fn);
  return {
    win,
    document,
    target,
    listeners,
    runTimers: () => {
      while (timers.length) timers.shift()();
    },
  };
}

function dispatch(env, event) {
  event.defaultPrevented = false;
  let stopped = false;
  let stoppedNow = false;
  event.preventDefault = () => {
    event.defaultPrevented = true;
  };
  event.stopPropagation = () => {
    stopped = true;
  };
  event.stopImmediatePropagation = () => {
    stopped = true;
    stoppedNow = true;
  };
  const chain = [env.win, env.document, env.target];
  const phases = [
    ...chain.map((n) => [n, 'capture']),
    ...chain.slice().reverse().map((n) => [n, 'bubble']),
  ];
  for (const [n, phase] of phases) {
    if (stoppedNow) break;
    const fns = (env.listeners.get(n) || { capture: [], bubble: [] })[phase];
    for (const fn of fns.slice()) {
      // A listener that throws does NOT abort dispatch in a real DOM - the error is reported and
      // the remaining listeners still run. Swallowing it here matters: without this the harness
      // would answer a different question than the browser does, and the "throwing page handler"
      // case below would pass or fail for the wrong reason.
      try {
        fn(event);
      } catch (ignored) {
        /* reported to the page's console in a browser; irrelevant here */
      }
      if (stoppedNow) break;
    }
    // Propagation halts after the current node's listeners finish.
    if (stopped) break;
  }
}

function inject(env) {
  vm.runInNewContext(PROBE, {
    window: env.win,
    navigator: env.win.navigator,
    setTimeout: env.win.setTimeout,
  });
}

/**
 * @param installFirst register the page's listener BEFORE the probe, i.e. the one ordering the
 *   probe cannot win. Document-start injection makes it the unusual case, not the normal one.
 */
function run({ platform = 'MacIntel', event, install, installFirst = false, bridge = true }) {
  const env = makeEnv(platform);
  const reports = [];
  if (bridge) env.win[consts.BRIDGE_PROPERTY] = { report: (v) => reports.push(v) };
  if (installFirst && install) install(env);
  inject(env);
  if (!installFirst && install) install(env);
  dispatch(env, event);
  env.runTimers();
  return reports;
}

const HANDLED = consts.VERDICT_HANDLED;
const FREE = consts.VERDICT_FREE;
const cmdF = (over = {}) => ({
  code: 'KeyF',
  keyCode: 70,
  metaKey: true,
  ctrlKey: false,
  shiftKey: false,
  altKey: false,
  ...over,
});
const ctrlF = (over = {}) => cmdF({ metaKey: false, ctrlKey: true, ...over });

console.log('find-key probe');

// --- who owns the chord ---------------------------------------------------
eq('a page listening for nothing leaves the chord free', run({ event: cmdF() }), [FREE]);
eq(
  'preventDefault on the target reads as handled',
  run({ event: cmdF(), install: (e) => e.target.addEventListener('keydown', (ev) => ev.preventDefault(), false) }),
  [HANDLED],
);
eq(
  'Sheets-shaped: document capture, preventDefault + stopPropagation, reads as handled',
  run({
    event: cmdF(),
    install: (e) =>
      e.document.addEventListener(
        'keydown',
        (ev) => {
          ev.preventDefault();
          ev.stopPropagation();
        },
        true,
      ),
  }),
  [HANDLED],
);
eq(
  'preventDefault by the very last handler still reads as handled',
  run({ event: cmdF(), install: (e) => e.win.addEventListener('keydown', (ev) => ev.preventDefault(), false) }),
  [HANDLED],
);
eq(
  'stopping propagation WITHOUT preventDefault does not claim the chord',
  run({
    event: cmdF(),
    install: (e) => e.document.addEventListener('keydown', (ev) => ev.stopPropagation(), true),
  }),
  [FREE],
);
eq(
  'a page that stops us immediately, ahead of injection, reports nothing (the host deadline covers it)',
  run({
    event: cmdF(),
    installFirst: true,
    install: (e) => e.win.addEventListener('keydown', (ev) => ev.stopImmediatePropagation(), true),
  }),
  [],
);

// --- which chord ----------------------------------------------------------
eq('Cmd+Shift+F is Focus Mode, not find', run({ event: cmdF({ shiftKey: true }) }), []);
eq('Cmd+Alt+F is not the find chord', run({ event: cmdF({ altKey: true }) }), []);
eq('F with no modifier is not the find chord', run({ event: cmdF({ metaKey: false }) }), []);
eq('Cmd+G is not the find chord', run({ event: { code: 'KeyG', keyCode: 71, metaKey: true } }), []);
eq('Cmd+Ctrl+F is not the find chord on macOS', run({ event: cmdF({ ctrlKey: true }) }), []);
eq('Ctrl+F is not the find chord on macOS', run({ event: ctrlF() }), []);
eq('Ctrl+F IS the find chord off macOS', run({ platform: 'Win32', event: ctrlF() }), [FREE]);
eq('Cmd+F is not the find chord off macOS', run({ platform: 'Win32', event: cmdF() }), []);
eq(
  'a frame with no event.code falls back to keyCode',
  run({ event: { keyCode: 70, metaKey: true, ctrlKey: false, shiftKey: false, altKey: false } }),
  [FREE],
);

// --- never break the page -------------------------------------------------
eq('no bridge published: no report, and nothing thrown', run({ event: cmdF(), bridge: false }), []);
{
  // Re-injection into one document must not double-report: the host injects per frame at document
  // start, and a single-page app can produce more than one call for the same context.
  const env = makeEnv('MacIntel');
  const reports = [];
  env.win[consts.BRIDGE_PROPERTY] = { report: (v) => reports.push(v) };
  inject(env);
  inject(env);
  dispatch(env, cmdF());
  env.runTimers();
  eq('re-injection into the same document is a no-op', reports, [FREE]);
}
{
  // A page throwing from its own handler must not stop us reporting - the probe's verdict is
  // about the key, not about whether the site is healthy.
  const reports = run({
    event: cmdF(),
    install: (e) =>
      e.document.addEventListener(
        'keydown',
        () => {
          throw new Error('page handler blew up');
        },
        true,
      ),
  });
  eq('a throwing page handler does not lose the verdict', reports, [FREE]);
}

console.log(failures === 0 ? '\nAll find-key probe checks passed.' : `\n${failures} check(s) failed.`);
process.exit(failures === 0 ? 0 : 1);
