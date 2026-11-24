/**
 * Page chrome shared by every view.
 *
 * The stylesheet is inline and nonce-stamped rather than a separate file: the
 * CSP is `default-src 'none'` with no `style-src 'self'`, and an edge function
 * has no static asset route to serve one from anyway.
 *
 * ## The palette is BOSS Blueprint, and it is a mirror
 *
 * Values are copied from `BossBlueprintColorScheme` /
 * `BossBlueprintLightColorScheme` in
 * `plugin-platform/plugin-ui-core/.../ui/BossThemes.kt`, which is the source of
 * truth. These pages are opened from the desktop panel, so a page in the old
 * amber identity next to a Blueprint app reads as a different product. If the
 * app palette moves, move these with it.
 *
 * SIGNAL IS THE FILL, SIGNALTEXT IS THE GLYPH. `--signal` (#0F5BFF) is 3.5:1 on
 * ink: enough for a control's edge or a filled button, not enough for text. Any
 * accent-coloured WORD uses `--signal-text` (#88A9FF, 8.1:1). The previous
 * stylesheet had `.linkish` and `.pill.admin` painting the accent as text, which
 * is the same regression the app corrected across ~90 call sites.
 *
 * ## Cloudflare must not rewrite the page
 *
 * api.risaboss.com is behind Cloudflare, whose Email Address Obfuscation rewrites
 * any address in an HTML response into a `__cf_email__` anchor plus an injected
 * decoder script. Our CSP is `default-src 'none'` with `script-src` nonce-only, so
 * that script is blocked and the address never decodes - every member on the
 * roster rendered as the literal words "email protected".
 *
 * The whole page is wrapped in Cloudflare's `<!--email_off-->` region rather than
 * each email field. The first fix was per-field, and the failure is per-RESPONSE:
 * Cloudflare rewrites any address it finds, not only ones from a column called
 * email, so a join request reading "reach me at me@corp.com" broke identically on
 * the very page the fix was for. One region covers the class; a list of fields
 * covers whatever someone remembered.
 *
 * ## Light mode is not decoration here
 *
 * The in-app browser bridges the host theme to `prefers-color-scheme`
 * (`FluckEngine` calls `engine.setTheme` from `BossThemeController`), so a page
 * that honours the media query tracks the app the user is looking at. Without
 * the light half, someone running Blueprint Light gets a dark page inside a
 * light app.
 */

import { esc } from "../utils/html.ts"

const STYLES = `
  :root {
    --ink: #05070B;
    --raised: #0E141E;
    --line: #1C2432;
    /* The edge of every input, select, textarea, secondary button, plain pill and
       table header. It was the one border NOT in the contrast matrix, and it failed
       the same 3:1 floor the status borders are held to - 1.63:1 on the card. That
       matters more than a card edge: the control's own fill (--ink) is nearly
       indistinguishable from the card (--raised), so this line is the only thing
       identifying where the control is. :focus-visible covers the focused state,
       not identification at rest. */
    --line-strong: #5A6474;
    --text: #E7EDFA;
    --text-2: #9AA7BB;
    --signal: #0F5BFF;
    --signal-dim: #0A45C4;
    --signal-wash: #0A1A3C;
    --signal-text: #88A9FF;
    /* Text-safe variants. On ink the status colours already clear 4.5:1, so these
       alias them; on paper they do not, which is why the pair exists at all. Same
       fill-versus-glyph split the app makes for signal, applied to status. */
    --ok-text: #2FD98A;
    --warn-text: #F0B429;
    /* alert-text alone is moved off its fill value. Every .danger button sits in a
       table cell, so hovering the button also hovers the row: the 12% tint from
       button.danger:hover composites on top of --token-wash, and the label is read
       on that doubled surface every single time it is read at all. At the fill
       value that was 4.34:1 here and 4.09:1 on paper. */
    --alert-text: #FF6868;
    --signal-on-wash: #88A9FF;
    /* Opaque border colours, not a percentage of the fill.
       Every status border used to be 45% of the DARK hue in BOTH themes, which put
       all six under the 3:1 WCAG 1.4.11 floor for a component boundary - 2.11:1 for
       the danger pill on ink, 1.33:1 for the warn pill on paper. Flattening the
       alpha by hand is also what let the light theme keep painting dark hues while
       its --alert token went unused by everything but one hover border. */
    --ok-border: #2FD98A;
    --warn-border: #F0B429;
    --alert-border: #FF5D5D;
    --signal-border: #0F5BFF;
    --on-signal: #FFFFFF;
    --token-wash: rgba(154, 167, 187, 0.12);
    --shadow: 0 1px 2px rgba(0, 0, 0, 0.4);
  }

  @media (prefers-color-scheme: light) {
    :root {
      --ink: #F5F7FB;
      --raised: #FFFFFF;
      --line: #DCE2EB;
      --line-strong: #868E9B;
      --text: #05070B;
      --text-2: #5C6372;
      --signal: #0F5BFF;
      --signal-dim: #0A45C4;
      --signal-wash: #DCE7FF;
      --signal-text: #0F5BFF;
      /* Solved against every surface each one actually lands on, which is more than
         the card: a status pill sits in a table cell, and "tbody tr:hover td" puts
         --token-wash behind it. Missing that surface is how the first pass shipped
         --ok-text at 3.01:1 on a hovered row while the contrast test read green -
         the test checked the card and the banner tint and no wash at all. */
      --ok-text: #177B4D;
      --warn-text: #926209;
      --alert-text: #B63340;
      --signal-on-wash: #0C3FBF;
      /* Darkened until each clears 3:1 on a white card. */
      --ok-border: #24A569;
      --warn-border: #B6891F;
      --alert-border: #FA5B5B;
      --signal-border: #0F5BFF;
      --on-signal: #FFFFFF;
      --token-wash: rgba(5, 7, 11, 0.06);
      --shadow: 0 1px 2px rgba(5, 7, 11, 0.06);
    }
  }

  * { margin: 0; padding: 0; box-sizing: border-box; }

  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background-color: var(--ink);
    color: var(--text);
    min-height: 100vh;
    line-height: 1.55;
    padding: 32px 24px 24px;
    -webkit-font-smoothing: antialiased;
  }
  .wrap { width: 100%; max-width: 960px; margin: 0 auto; }

  /* ---- page header ---------------------------------------------------- */

  header.page { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 6px; }
  header.page h1 { font-size: 28px; font-weight: 650; letter-spacing: -0.5px; line-height: 1.2; }

  /* An organisation slug is a machine identifier, validated ^[a-z][a-z0-9_]{1,30}$.
     Mono against a wash is what separates the key from the prose beside it, and it
     matches how the desktop panel renders the same value. */
  .slug {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 13px; color: var(--text-2);
    background-color: var(--token-wash);
    border-radius: 5px; padding: 2px 7px;
  }
  .sub { color: var(--text-2); font-size: 15px; margin-bottom: 26px; max-width: 68ch; }
  /* A website may be 500 characters with no spaces, and it is rendered as its own
     link text. max-width cannot break an unbroken token, so without this it runs
     off the page rather than wrapping. */
  .sub a { overflow-wrap: anywhere; }

  /* ---- tabs ------------------------------------------------------------ */

  .tabs { display: flex; gap: 6px; margin-bottom: 22px; flex-wrap: wrap; }
  .tabs a {
    color: var(--text-2); text-decoration: none; font-size: 14px; padding: 7px 15px;
    border: 1px solid var(--line); border-radius: 7px; background-color: var(--raised);
  }
  .tabs a:hover { color: var(--text); border-color: var(--line-strong); }
  /* Filled, so white sits on the signal rather than the signal sitting on ink. */
  .tabs a.active {
    color: var(--on-signal); background-color: var(--signal);
    border-color: var(--signal); font-weight: 600;
  }
  .tabs a.active:hover { color: var(--on-signal); background-color: var(--signal-dim); }

  /* ---- cards ----------------------------------------------------------- */

  section.card {
    background-color: var(--raised); border: 1px solid var(--line); border-radius: 10px;
    padding: 22px; margin-bottom: 16px; box-shadow: var(--shadow);
  }

  /* The scroll lives on a wrapper around the table, not on the card.
     Two reasons it moved off .card:
       - overflow-x on a block computes overflow-y to auto as well, so every card
         became a scroll container in both axes and the heading and hint scrolled
         sideways along with the table;
       - a scrollable region is only operable from a keyboard if it can take
         focus, and these tables are entirely read-only, so they contain nothing
         focusable. Without tabindex a keyboard user still could not reach
         "Joined" or "Permissions" on a narrow viewport - the same content loss
         the old display:none rule caused, in a different modality. */
  .scroller { overflow-x: auto; }
  .scroller:focus-visible { outline: 2px solid var(--signal); outline-offset: 2px; }
  /* A real heading, not the uppercase micro-label the desktop panel uses. The
     admin page has six substantial sections on a 960px canvas; an eyebrow is the
     right weight for a 300px sidebar and too quiet to structure a page. */
  section.card > h2 {
    font-size: 16px; font-weight: 650; margin-bottom: 5px;
    letter-spacing: -0.2px; color: var(--text);
  }
  section.card > p.hint { color: var(--text-2); font-size: 13px; margin-bottom: 18px; max-width: 68ch; }

  /* ---- tables ---------------------------------------------------------- */

  table { width: 100%; border-collapse: collapse; font-size: 14px; }
  th {
    text-align: left; color: var(--text-2); font-weight: 600; font-size: 11px;
    text-transform: uppercase; letter-spacing: 0.6px; white-space: nowrap;
    padding: 0 14px 8px 0; border-bottom: 1px solid var(--line-strong);
  }
  td { padding: 11px 14px 11px 0; border-bottom: 1px solid var(--line); vertical-align: middle; }
  tr:last-child td { border-bottom: none; }
  tbody tr:hover td { background-color: var(--token-wash); }

  /* ---- pills and identifiers ------------------------------------------- */

  .pill {
    display: inline-block; font-size: 11px; padding: 2px 8px; border-radius: 5px;
    border: 1px solid var(--line-strong); color: var(--text-2);
    margin: 1px 4px 1px 0; white-space: nowrap;
  }
  /* A wash behind readable text, never the accent AS text. */
  .pill.admin {
    border-color: var(--signal-border); background-color: var(--signal-wash);
    color: var(--signal-on-wash); font-weight: 600;
  }
  .pill.ok { border-color: var(--ok-border); color: var(--ok-text); }
  .pill.warn { border-color: var(--warn-border); color: var(--warn-text); }
  .mono {
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 12px;
  }
  /* A mono pill is an identifier, not a status, so it drops the outline and
     takes a neutral wash - a row of outlined boxes shouts louder than the words. */
  .pill.mono { border-color: transparent; background-color: var(--token-wash); color: var(--text-2); }

  /* The DNS record a person has to retype into their registrar.
     LAYOUT ONLY - every colour here is a token already asserted by contrast.test.ts on these
     surfaces (--text-2 on card and on washOnCard). Introducing a new colour pair would need a new
     USAGE entry there, and an unasserted pair is how the sub-AA borders shipped the first time. */
  /* The rendered README. LAYOUT ONLY - every colour is a token contrast.test.ts already asserts
     on this surface (--text and --text-2 on card, and on the --token-wash surface). No new pair is
     introduced here, which is the rule that kept the sub-AA borders from shipping twice.

     Scoped under .md so a README cannot restyle the page around it: these rules only ever apply
     inside the one block the renderer writes. */
  .md { font-size: 13px; color: var(--text-2); line-height: 1.55; overflow-wrap: anywhere; }
  .md > :first-child { margin-top: 0; }
  .md > :last-child { margin-bottom: 0; }
  .md h3, .md h4, .md h5, .md h6 { color: var(--text); margin: 16px 0 6px; line-height: 1.3; }
  .md h3 { font-size: 15px; }
  .md h4 { font-size: 13px; }
  .md h5, .md h6 { font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
  .md p { margin: 0 0 10px; }
  .md ul, .md ol { margin: 0 0 10px; padding-left: 20px; }
  .md li { margin: 2px 0; }
  .md a { color: var(--text); text-decoration: underline; }
  .md code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
             background: var(--token-wash); border-radius: 3px; padding: 1px 4px; }
  /* The <code> inside a fence must not take the inline chip treatment as well. */
  .md pre.md-code { background: var(--token-wash); border-radius: 4px; padding: 10px 12px;
                    margin: 0 0 10px; overflow-x: auto; }
  .md pre.md-code code { background: none; padding: 0; font-size: 12px; }
  .md blockquote { margin: 0 0 10px; padding: 2px 0 2px 10px; border-left: 2px solid var(--line);
                   color: var(--text-2); }
  .md hr { border: 0; border-top: 1px solid var(--line); margin: 14px 0; }
  .md table { border-collapse: collapse; font-size: 12px; }
  .md th, .md td { border: 1px solid var(--line); padding: 4px 8px; text-align: left; }
  .md th { color: var(--text); }
  /* Alignment as classes, because style-src is nonce-only and an inline style attribute is
     dropped by the browser without a word. */
  .md .md-center { text-align: center; }
  .md .md-right { text-align: right; }
  /* The pager under a paginated table. LAYOUT ONLY - both colours are tokens contrast.test.ts
     already asserts on this surface. Rendered only when there is more than one page. */
  .pager { display: flex; align-items: center; gap: 12px; margin-top: 10px; font-size: 12px; }
  .pager a { color: var(--text); text-decoration: underline; }
  .pager .muted { color: var(--text-2); }
  .pager-state { color: var(--text-2); }
  .dns { display: flex; flex-direction: column; gap: 4px; }
  .dns-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  /* Fixed width so Name/Type/Value line up as a column and the records read as one block. */
  .dns-key { font-size: 11px; color: var(--text-2); min-width: 40px; }
  /* The value is long and arbitrary; let it wrap rather than widen the table past the viewport.
     anywhere, not break-all: a hostname then breaks at its dots by preference. */
  .dns .pill.mono { white-space: normal; overflow-wrap: anywhere; }
  button.copy { padding: 1px 8px; font-size: 11px; }

  /* ---- forms ----------------------------------------------------------- */

  label { display: block; font-size: 13px; color: var(--text-2); margin-bottom: 6px; }
  input[type=text], input[type=number], select, textarea {
    width: 100%; background-color: var(--ink); color: var(--text);
    border: 1px solid var(--line-strong); border-radius: 7px; padding: 9px 11px;
    font-size: 14px; font-family: inherit;
  }
  input::placeholder, textarea::placeholder { color: var(--text-2); }
  input:hover, select:hover, textarea:hover { border-color: var(--signal-text); }
  /* focus-visible, so a mouse click does not paint a ring the keyboard user needs. */
  input:focus-visible, select:focus-visible, textarea:focus-visible,
  button:focus-visible, a:focus-visible {
    outline: 2px solid var(--signal); outline-offset: 2px;
  }
  /* Inside a table cell a control sizes to its content. The page-level width:100%
     made the role select fill the cell, which pushed its own Assign button onto a
     second line and left Remove floating beside the pair at a different height. */
  td select, td input[type=text], td input[type=number] { width: auto; min-width: 150px; }
  input[type=checkbox] { accent-color: var(--signal); width: 15px; height: 15px; }
  .row { display: flex; gap: 14px; flex-wrap: wrap; margin-bottom: 16px; }
  .row > div { flex: 1 1 200px; }
  .checkline { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
  .checkline label { margin: 0; }

  /* ---- buttons --------------------------------------------------------- */

  /* A link that acts as the page's primary action (the boss:// deep link on a plugin page).
     Shares the button rule below rather than restating its colours, so the two cannot drift and
     no new colour pair is introduced for contrast.test.ts to miss. */
  a.button {
    display: inline-block; text-decoration: none; text-align: center;
  }
  button, a.button {
    background-color: var(--signal); color: var(--on-signal); border: 1px solid var(--signal);
    border-radius: 7px; padding: 8px 16px; font-size: 14px; font-weight: 600;
    cursor: pointer; font-family: inherit;
  }
  button:hover { background-color: var(--signal-dim); border-color: var(--signal-dim); }
  button:active { transform: translateY(1px); }
  button.secondary {
    background-color: transparent; color: var(--text-2); border-color: var(--line-strong);
    font-weight: 500;
  }
  button.secondary:hover { background-color: var(--token-wash); color: var(--text); border-color: var(--line-strong); }
  button.danger {
    background-color: transparent; color: var(--alert-text);
    border-color: var(--alert-border); font-weight: 500;
  }
  button.danger:hover { background-color: rgba(255, 93, 93, 0.12); border-color: var(--alert-border); }
  /* inline-flex, not inline: a form holding a select AND a button needs the two to
     stay on one line and share a gap. Two adjacent forms in one cell (approve and
     reject) get the margin, since flex gap does not reach across siblings. */
  form.inline { display: inline-flex; align-items: center; gap: 8px; vertical-align: middle; }
  form.inline + form.inline { margin-left: 8px; }

  /* ---- banners --------------------------------------------------------- */

  .banner {
    border-radius: 8px; padding: 12px 15px; margin-bottom: 18px; font-size: 14px;
    border: 1px solid transparent;
  }
  .banner.error { background-color: rgba(255, 93, 93, 0.10); border-color: var(--alert-border); color: var(--alert-text); }
  .banner.ok { background-color: rgba(47, 217, 138, 0.10); border-color: var(--ok-border); color: var(--ok-text); }

  /* ---- stats ----------------------------------------------------------- */

  /* inline-block with a bottom margin, so a row of five wraps onto a second line
     instead of running past the card edge. There are up to five, and "joining"
     carries values like "request to join", so the overflow was reachable rather
     than theoretical. Top-aligned, or a wrapped row hangs off the tallest value. */
  .stat { display: inline-block; margin: 0 30px 8px 0; vertical-align: top; }
  /* An explicit line box, shared by both sizes. Unitless line-height scaled with
     font-size, so the 21px figure and the 15px phrase produced boxes 6px apart and
     their captions no longer sat on one line. */
  .stat b {
    display: block; font-size: 21px; font-weight: 650; letter-spacing: -0.3px;
    line-height: 32px; font-variant-numeric: tabular-nums;
  }
  .stat.phrase b { font-size: 15px; font-weight: 600; letter-spacing: 0; }
  .stat span {
    color: var(--text-2); font-size: 11px; text-transform: uppercase; letter-spacing: 0.6px;
  }

  /* ---- misc ------------------------------------------------------------ */

  .empty { color: var(--text-2); font-size: 14px; padding: 10px 0; }
  footer.page {
    color: var(--text-2); font-size: 12px; margin-top: 34px; padding-top: 16px;
    border-top: 1px solid var(--line); text-align: center;
  }
  a { color: var(--signal-text); }

  /* Utility classes, because every inline style= attribute is DROPPED by our own
     CSP: style-src is nonce-only, CSP3 falls style-src-attr back to it, and a
     nonce cannot apply to an attribute. Inline styles rendered nothing and logged
     a violation on every page. */
  .spaced { margin-top: 16px; }
  .tight { margin-top: 12px; }
  /* section.card.highlight, not .highlight: "section.card" sets border-color at
     specificity (0,1,1) and a bare ".highlight" is (0,1,0), so the accent lost and
     this rule had never rendered. */
  section.card.highlight { border-color: var(--signal); }

  @media (max-width: 620px) {
    body { padding: 18px 14px 14px; }
    header.page h1 { font-size: 23px; }
    section.card { padding: 16px; }
    .stat { margin-right: 22px; }
  }

  @media (prefers-reduced-motion: reduce) {
    * { animation: none !important; transition: none !important; }
    button:active { transform: none; }
  }
`

export type BannerKind = "error" | "ok"

/**
 * Click-to-copy for anything carrying `data-copy`.
 *
 * Opt-in per page via [LayoutOptions.script] rather than shipped on every response, so a page with
 * nothing to copy carries no script at all.
 *
 * EVENT DELEGATION, because it has to be. The CSP is `script-src 'nonce-...'` with no
 * `unsafe-inline`, so an `onclick=` attribute is dead on arrival - a nonce cannot apply to an
 * attribute. One nonced block listening on the document is the shape that works, and it also
 * survives rows being re-rendered.
 *
 * The value is read from the attribute rather than from the element's text: the displayed record
 * may be wrapped or truncated by CSS later, and copying what was DISPLAYED would then quietly hand
 * someone a broken TXT value.
 *
 * `execCommand` is the fallback for good reason, not superstition: `navigator.clipboard` is
 * undefined outside a secure context and its promise rejects when the document is not focused, and
 * a DNS record you cannot copy is exactly the failure this exists to remove.
 */
const COPY_SCRIPT = `
(function () {
  var RESET_MS = 1200;
  function flash(button, message) {
    if (button.dataset.busy) return;
    button.dataset.busy = "1";
    var original = button.textContent;
    button.textContent = message;
    setTimeout(function () {
      button.textContent = original;
      delete button.dataset.busy;
    }, RESET_MS);
  }
  function legacyCopy(text) {
    var area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    area.style.position = "fixed";
    area.style.top = "-1000px";
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
    document.body.removeChild(area);
    return ok;
  }
  document.addEventListener("click", function (event) {
    var target = event.target;
    if (!target || typeof target.closest !== "function") return;
    var button = target.closest("button[data-copy]");
    if (!button) return;
    event.preventDefault();
    var text = button.getAttribute("data-copy") || "";
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(
        function () { flash(button, "Copied"); },
        function () { flash(button, legacyCopy(text) ? "Copied" : "Press Ctrl+C"); }
      );
      return;
    }
    flash(button, legacyCopy(text) ? "Copied" : "Press Ctrl+C");
  });
})();
`

/** The click-to-copy behaviour, for a page that renders `data-copy` buttons. */
export const COPY_BEHAVIOUR = COPY_SCRIPT

export interface LayoutOptions {
  title: string
  nonce: string
  body: string
  /** Rendered above the body when present. */
  banner?: { kind: BannerKind; message: string } | null
  /**
   * JavaScript to inline in a nonced block, or omitted for no script at all.
   *
   * This is OUR OWN source only - never a value derived from a request or from an organisation
   * field. It is written into a `<script>` verbatim, so anything interpolated into it would be
   * executing, not displaying.
   */
  script?: string
}

/**
 * Wrap page content in the document shell.
 *
 * The banner carries no `role="status"`. A live region is announced when its
 * content CHANGES; this one is server-rendered into the initial document, so the
 * role would never fire and would only misdescribe the element. It is early in
 * the reading order instead, which is what actually reaches a screen reader here.
 *
 * `body` is expected to be already-escaped markup built by the caller. The
 * banner message is escaped HERE, because it is the one string that routinely
 * originates from an RPC error and would otherwise be interpolated raw.
 */
export function layout({ title, nonce, body, banner, script }: LayoutOptions): string {
  const bannerHtml = banner
    ? `<div class="banner ${
      banner.kind === "ok" ? "ok" : "error"
    }">${esc(banner.message)}</div>`
    : ""

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="dark light">
<title>${esc(title)}</title>
<style nonce="${esc(nonce)}">${STYLES}</style>
</head>
<body>
<!--email_off-->
<div class="wrap">
${bannerHtml}
${body}
<footer class="page">BOSS Organisation</footer>
</div>
<!--/email_off-->
${script ? `<script nonce="${esc(nonce)}">${script}</script>` : ""}
</body>
</html>`
}

/**
 * The tab strip.
 *
 * `current` selects the active tab. The admin tab is only emitted when the
 * caller says the viewer is an admin, and that flag always comes from a live
 * probe -- hiding it is cosmetic, the route re-checks regardless.
 */
export function tabs(
  basePath: string,
  slug: string,
  current: "overview" | "admin",
  isAdmin: boolean,
): string {
  const overviewHref = `${basePath}/o/${encodeURIComponent(slug)}`
  const items = [
    `<a href="${esc(overviewHref)}"${
      current === "overview" ? ' class="active" aria-current="page"' : ""
    }>Overview</a>`,
  ]
  if (isAdmin) {
    items.push(
      `<a href="${esc(overviewHref)}/admin"${
        current === "admin" ? ' class="active" aria-current="page"' : ""
      }>Configuration</a>`,
    )
  }
  return `<nav class="tabs" aria-label="Organisation sections">${items.join("")}</nav>`
}

/** A hidden CSRF field. Every mutating form must include one. */
export function csrfField(fieldName: string, token: string): string {
  return `<input type="hidden" name="${esc(fieldName)}" value="${esc(token)}">`
}
