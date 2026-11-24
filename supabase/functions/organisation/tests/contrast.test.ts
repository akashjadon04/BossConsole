/**
 * Every colour pair the pages actually paint must clear its WCAG floor.
 *
 * This exists because looking at the page is not enough. The light theme was
 * screenshotted, read as fine, and had six sub-AA pairs in it. None of that is
 * visible to a person who already knows what the words say.
 *
 * ## Both halves are read out of the stylesheet
 *
 * Tokens AND the translucent surfaces are parsed from `layout.ts`. The first
 * version parsed the tokens and hardcoded the surfaces, and that is exactly how
 * it missed three failures: it knew about the card and the banner tint, and had
 * no `--token-wash` surface at all - which is where every role name, permission
 * string and organisation slug is painted, and what `tbody tr:hover td` puts
 * behind a whole row. `--ok-text` was at 3.01:1 on a hovered row while this file
 * reported green.
 *
 * So the rule is: if a background is a colour with alpha, this file composites it
 * from the real declaration rather than from a copy. A copy drifts, and a test
 * that agrees with a stale copy of the thing it guards is worse than no test.
 *
 * ## Surfaces are per-token, because they are not interchangeable
 *
 * `--warn-text` colours a pill that can sit on a hovered row, but never inside a
 * `.pill.mono`, so it is not held to the doubled wash. `--alert-text` IS held to a
 * doubled surface, because a .danger button's own hover fill composites on top of
 * the row wash beneath it. Holding every token to every surface would force the
 * palette darker than the pages need; holding one to too few is how the danger
 * label shipped at 4.09:1.
 */

import { assert } from "@std/assert"

const STYLESHEET = await Deno.readTextFile(new URL("../views/layout.ts", import.meta.url))

const TEXT_FLOOR = 4.5
/** WCAG 1.4.11: a UI component boundary, not text. */
const UI_FLOOR = 3.0

type Palette = Record<string, string>

function tokensIn(block: string): Palette {
  const out: Palette = {}
  for (const [, name, hex] of block.matchAll(/--([a-z0-9-]+):\s*(#[0-9A-Fa-f]{6})/g)) {
    out[name] = hex
  }
  return out
}

/** `--token-wash: rgba(r, g, b, a)` out of a palette block, as a hex plus alpha. */
function washIn(block: string): { hex: string; alpha: number } {
  const m = block.match(/--token-wash:\s*rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)/)
  assert(m, "no --token-wash rgba() found; the wash surfaces below would be invented")
  const [, r, g, b, a] = m
  const hex = "#" + [r, g, b].map((v) => Number(v).toString(16).padStart(2, "0")).join("")
  return { hex, alpha: Number(a) }
}

/**
 * The tint `button.danger:hover` paints, read from the rule.
 *
 * This one composites on top of the ROW hover wash, not on the card: every
 * .danger button in these pages lives in a table cell, so hovering the button
 * necessarily hovers its row. That doubled surface is the only state the label is
 * ever read in, and it was the last surface this file did not model.
 */
function dangerHoverFill(): { hex: string; alpha: number } {
  const rule = STYLESHEET.match(/button\.danger:hover\s*\{[^}]*\}/)
  assert(rule, "no button.danger:hover rule found")
  const m = rule[0].match(/background-color:\s*rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)/)
  assert(m, "button.danger:hover has no rgba background to composite")
  const [, r, g, b, a] = m
  const hex = "#" + [r, g, b].map((v) => Number(v).toString(16).padStart(2, "0")).join("")
  return { hex, alpha: Number(a) }
}

/**
 * The tint behind `.banner.ok` / `.banner.error`, read from the rule itself.
 *
 * Hardcoding these was the other half of the same mistake: the alpha could be
 * raised to 0.14 in the stylesheet and this file would keep re-deriving the old
 * 0.10 surface and keep reporting pass.
 */
function bannerTint(kind: "ok" | "error"): { hex: string; alpha: number } {
  const rule = STYLESHEET.match(new RegExp(`\\.banner\\.${kind}\\s*\\{[^}]*\\}`))
  assert(rule, `no .banner.${kind} rule found`)
  const m = rule[0].match(/background-color:\s*rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)/)
  assert(m, `.banner.${kind} has no rgba background to composite`)
  const [, r, g, b, a] = m
  const hex = "#" + [r, g, b].map((v) => Number(v).toString(16).padStart(2, "0")).join("")
  return { hex, alpha: Number(a) }
}

function channel(value: number): number {
  const c = value / 255
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

function luminance(hex: string): number {
  const h = hex.replace("#", "")
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

function contrast(a: string, b: string): number {
  const [la, lb] = [luminance(a), luminance(b)]
  const [hi, lo] = la > lb ? [la, lb] : [lb, la]
  return (hi + 0.05) / (lo + 0.05)
}

/** Flatten a translucent fill onto its backdrop, the way the browser composites it. */
function over(fill: string, alpha: number, backdrop: string): string {
  const parse = (hex: string) =>
    [0, 2, 4].map((i) => parseInt(hex.replace("#", "").slice(i, i + 2), 16))
  const [f, b] = [parse(fill), parse(backdrop)]
  return "#" + f.map((v, i) => Math.round(v * alpha + b[i] * (1 - alpha)))
    .map((v) => v.toString(16).padStart(2, "0")).join("")
}

/** Every background any text in these pages lands on, composited. */
function surfaces(theme: Palette, block: string) {
  const wash = washIn(block)
  const ok = bannerTint("ok")
  const err = bannerTint("error")
  const washOnCard = over(wash.hex, wash.alpha, theme.raised)
  return {
    card: theme.raised,
    page: theme.ink,
    // .pill.mono in a card, and any cell in a hovered row.
    washOnCard,
    // .slug sits in the page header, not in a card.
    washOnPage: over(wash.hex, wash.alpha, theme.ink),
    // .pill.mono inside a hovered row stacks its own wash on the row's.
    washStacked: over(wash.hex, wash.alpha, washOnCard),
    okBanner: over(ok.hex, ok.alpha, theme.raised),
    // Two translucent layers: the row wash, then the button's own hover fill.
    dangerHover: (() => {
      const d = dangerHoverFill()
      return over(d.hex, d.alpha, washOnCard)
    })(),
    errorBanner: over(err.hex, err.alpha, theme.raised),
    signalWash: theme["signal-wash"],
    signal: theme.signal,
    signalDim: theme["signal-dim"],
  }
}

type SurfaceName = keyof ReturnType<typeof surfaces>

/** What each token colours, and therefore what it must clear. */
const USAGE: Array<{ token: string; where: string; on: SurfaceName[]; floor?: number }> = [
  {
    token: "text",
    // washOnCard because `tbody tr:hover td` puts the wash behind ordinary cell
    // text, and button.secondary:hover paints --text on it.
    where: "body copy, including a hovered row",
    on: ["card", "page", "washOnCard"],
  },
  {
    // The widest reach in the sheet: hints, table headers, plain and mono pills,
    // the slug, empty states, the footer and tab labels.
    token: "text-2",
    where: "hints, headers, identifier pills and the slug",
    on: ["card", "page", "washOnCard", "washOnPage", "washStacked"],
  },
  {
    token: "signal-text",
    where: "links and tab labels",
    on: ["card", "page", "washOnCard"],
  },
  {
    token: "signal-on-wash",
    where: "the admin pill",
    // Its own wash is opaque, so a hovered row does not show through it.
    on: ["signalWash"],
  },
  {
    token: "ok-text",
    where: "ok pills and the ok banner",
    on: ["card", "washOnCard", "okBanner"],
  },
  {
    token: "warn-text",
    where: "warn pills",
    on: ["card", "washOnCard"],
  },
  {
    token: "alert-text",
    where: "the danger button and the error banner",
    // dangerHover is the binding one and was missing: 4.09:1 on paper.
    on: ["card", "washOnCard", "errorBanner", "dangerHover"],
  },
  {
    token: "on-signal",
    // signalDim is the hover fill for both the primary button and the active tab,
    // so the label is read on it as often as on signal itself.
    where: "a primary button's label, at rest and hovered",
    on: ["signal", "signalDim"],
  },
  {
    // Not text. `signal` is 3.8:1 on ink BY DESIGN, which is why signal-text
    // exists. Holding it to 4.5 would "fix" it by brightening the brand blue.
    token: "signal",
    where: "a control border",
    on: ["page"],
    floor: UI_FLOOR,
  },
  // Borders were not checked at all until now, and every one of them failed: each
  // was 45% of the DARK hue in BOTH themes, giving 2.11:1 for the danger pill on
  // ink and 1.33:1 for the warn pill on paper. They are opaque tokens now, held to
  // the 3:1 WCAG 1.4.11 floor against the card they sit on.
  { token: "ok-border", where: "an ok pill's edge", on: ["card"], floor: UI_FLOOR },
  { token: "warn-border", where: "a warn pill's edge", on: ["card"], floor: UI_FLOOR },
  { token: "alert-border", where: "the danger button's edge", on: ["card"], floor: UI_FLOOR },
  { token: "signal-border", where: "the admin pill's edge", on: ["card"], floor: UI_FLOOR },
  // The edge of every form control. Checked against BOTH the card and the control's
  // own fill, because those two are nearly the same colour in both themes - so this
  // line is the only thing identifying the control's boundary at rest.
  {
    token: "line-strong",
    where: "form control and pill edges",
    on: ["card", "page"],
    floor: UI_FLOOR,
  },
]

function palettes(): Array<{ name: string; theme: Palette; block: string }> {
  const darkBlock = STYLESHEET.split(":root {")[1].split("}")[0]
  const lightBlock = STYLESHEET.split("prefers-color-scheme: light")[1].split("}")[0]
  const dark = tokensIn(darkBlock)
  return [
    { name: "dark", theme: dark, block: darkBlock },
    // The light block only overrides; the cascade leaves the rest of :root standing.
    { name: "light", theme: { ...dark, ...tokensIn(lightBlock) }, block: lightBlock },
  ]
}

for (const { name, theme, block } of palettes()) {
  const surf = surfaces(theme, block)
  for (const use of USAGE) {
    for (const surfaceName of use.on) {
      Deno.test(`${name}: ${use.token} on ${surfaceName} (${use.where})`, () => {
        const fg = theme[use.token]
        assert(fg, `no --${use.token} in the ${name} palette`)
        const bg = surf[surfaceName]
        assert(bg, `no ${surfaceName} surface`)
        const floor = use.floor ?? TEXT_FLOOR
        const ratio = contrast(fg, bg)
        assert(
          ratio >= floor,
          `${name}: --${use.token} (${fg}) on ${surfaceName} (${bg}) is ` +
            `${ratio.toFixed(2)}:1, needs ${floor}:1`,
        )
      })
    }
  }
}

Deno.test("the light palette really does override the colours under test", () => {
  // Without this, a light block that stopped redefining these would leave every
  // light assertion silently re-checking the dark values and still passing.
  const [dark, light] = palettes()
  for (const token of ["ok-text", "warn-text", "alert-text", "signal-on-wash", "text-2", "ink"]) {
    assert(
      dark.theme[token] !== light.theme[token],
      `--${token} is identical in both palettes, so the light assertions prove nothing about it`,
    )
  }
})

Deno.test("each palette block parses whole, not truncated at a stray brace", () => {
  // palettes() splits on the first "}", so ONE closing brace inside a comment in
  // either block silently shortens the palette and tokensIn returns fewer tokens.
  // The assertions above only catch a token that is both missing AND under test,
  // so a truncation that drops an unchecked token passes unnoticed. A floor on the
  // count makes that loud.
  // Counted on each block AS PARSED, not on the merged palette. light is built as
  // {...dark, ...light}, so counting the merge meant a light block truncated to
  // zero tokens still reported the full dark count - the guard could not fail for
  // the palette it was most needed for.
  for (const { name, block } of palettes()) {
    const own = Object.keys(tokensIn(block)).length
    assert(own >= 18, `the ${name} block parsed only ${own} tokens - truncated at a brace?`)
  }
})

Deno.test("each palette declares its own token wash", () => {
  // The wash defines three of the surfaces above. If a palette stopped declaring
  // one, washIn would fall back to the other palette's block and every wash
  // assertion would be measured against the wrong backdrop.
  const [dark, light] = palettes()
  const a = washIn(dark.block)
  const b = washIn(light.block)
  assert(
    a.hex !== b.hex || a.alpha !== b.alpha,
    "both palettes declare the same --token-wash; one of them is not being read",
  )
})
