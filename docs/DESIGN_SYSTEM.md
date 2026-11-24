# BOSS Design System - "Operator's Console"

One visual language shared by **BossConsole** (the host) and **BossTerm** (the
terminal surface). This is the canonical spec; the [**visual styleguide**](design-system.html)
(a self-contained HTML reference - open it in a browser) is the companion reference,
and the tokens themselves ship as code (see [Where it lives](#where-it-lives)).

---

## 1. Direction

Three rules drive every token. When a choice is unclear, return here.

1. **The cell is the unit.** The terminal character cell (~8.4 × 17 px at 14sp
   MesloLGS) is the grid the whole UI snaps to. Chrome borrows the terminal's
   discipline - 8.dp base spacing, hairline borders, high density - not the
   other way around.
2. **Exactly one signal.** One color means *live / active / now*: the primary
   action, the focused field, the selected tab, the cursor. Nothing else competes
   for it. A second color, `data`, carries links and data. *Which* hues those are
   is the active theme's business - NVIDIA green under **NVIDIA** (what macOS
   and Linux open with), electric blue under **Blueprint**, amber and cyan under
   **Operator** - but a theme never gets three.
3. **Quiet everywhere else.** Surfaces separate by tint, not shadow. Borders are
   hairlines. Density is high and calm so your output is the loudest thing on
   screen.

The deliberate, non-default choice throughout: **monospace is the display/brand
voice**, not just code. Grounded in a terminal-first product.

---

## 2. Color

Host chrome and the terminal share one floor (`ink`), so a panel and the shell
it wraps read as one continuous surface. `signal` and `data` are the only
saturated colors that appear in chrome.

### Selectable themes

Every token below is a *slot*, not a hex. The active theme fills the slots;
components only ever name slots. Themes live in
`…/ui/BossThemes.kt` and the picker renders `BossThemes.all` in order, so adding
a theme is one `BossAppTheme` plus one list entry.

| Theme | id | | Identity |
|-------|----|-|----------|
| **Blueprint** | `blueprint` | dark | Electric blue on ink - matches [bossconsole.ai](https://bossconsole.ai). **The class default** (what a settings file saying `{}` means). |
| **Blueprint Light** | `blueprint-light` | light | The same blue, re-grounded on the site's `--paper`. **The default on Windows.** |
| **Operator** | `operator` | dark | The original amber-on-ink identity |
| **Daylight** | `daylight` | light | Clean light theme |
| **Clean** | `clean` | dark | Neutral charcoal, steel-blue accent |
| **NVIDIA** | `nvidia` | dark | NVIDIA green `#76B900` on black - the [nvidia.com](https://www.nvidia.com/en-us/) look. **The default on macOS and Linux.** |

The choice is explicit and persisted (`~/.boss/app-theme-settings.json`); the OS
theme setting is never consulted.

**The default is per-platform.** macOS and Linux open on NVIDIA
(`BossThemes.UNIX_DEFAULT_ID`), Windows on Blueprint Light
(`BossThemes.WINDOWS_DEFAULT_ID`). `BossThemes.defaultIdFor(isWindows)` is the only
place that branches; `BossThemes.DEFAULT_ID` stays the *class* default, which is
what an existing settings file means when it says nothing, and is now no longer
what any platform opens with.

Windows and the Unix default differ in **brand**, not merely in value, which was
not true while both were Blueprint. That is forced rather than chosen: NVIDIA has
no light half to reach for, because `#76B900` is 2.4:1 on white and darkening it
enough to clear the 3:1 component floor stops it being the brand green. Windows
keeps the identity that does have both halves.

**Who a default change re-skins.** `AppThemeSettingsManager` writes that file only
from `select()` - `ensureInitialized()` resolves the id without saving. The file's
existence is therefore the record that someone chose, and the two absences are not
the same thing:

| On disk | Means | Resolves to |
|---------|-------|-------------|
| no file | nobody has ever picked | the platform default |
| a file with no `appThemeId` | someone picked the class default, under the old `encodeDefaults = false` writer | `DEFAULT_ID`, on every platform |
| a file with an `appThemeId` | someone picked that | that theme |

The middle row is the one that costs something to get wrong: resolving it from the
platform default would flip a Windows user who deliberately chose Blueprint back to
light on their next launch, with no way to make the choice stick. Writes now use
`encodeDefaults = true`, so files written from here on always name the id and the
ambiguity ends with the files that already exist.

`ensureInitialized()` still does not persist what it resolved. Pinning a platform
default on first launch would mean the next change to what a platform opens with
reaches nobody who has ever run the app.

That property is why the four mirrors below have to agree on the default, not just
on the palettes.

#### Blueprint - the class-default palette

Lifted from bossconsole.ai's stylesheet, or composited from it (a site alpha over
the site's own floor). The site's hero mocks the app itself (`.console-frame` /
`.console-topbar` / `.console-sidebar` / `.agent-strip`), so this ladder is the
site's rather than an interpretation of it.

| Token | Blueprint | Blueprint Light | Source on the site |
|-------|-----------|-----------------|--------------------|
| `ink` | `#05070B` | `#F5F7FB` | `--ink` / `--paper` |
| `panel` | `#080B11` | `#FFFFFF` | `.console-frame` / `.feature-card` |
| `raised` | `#0E141E` | `#FFFFFF` | near `--ink-2 #0b1019` |
| `line` | `#1C2432` | `#DCE2EB` | `--line-dark` / `--line` over the floor |
| `lineStrong` | `#2E3B4F` | `#A8B2C2` | `#ffffff4d` over ink; light edge softened from the site's full-ink card border |
| `textPrimary` | `#E7EDFA` | `#05070B` | site `#e7edfa` / `--ink` |
| `textSecondary` | `#9AA7BB` | `#687081` | `.eyebrow` / `--muted` |
| `textMuted` | `#69768B` | `#9AA3B2` | `.console-topbar` |
| `signal` | `#0F5BFF` | `#0F5BFF` | `--blue` |
| `signalDim` | `#0A45C4` | `#0A45C4` | pressed / variant |
| `signalWash` | `#0A1A3C` | `#DCE7FF` | `.console-sidebar .selected` / `--blue-soft` |
| `signalText` | `#88A9FF` | `#0F5BFF` | the site's link-blue family (see below) |
| `data` | `#88A9FF` | `#0C3FBF` | `.audit-line svg` `#8af` |
| `ok` | `#2FD98A` | `#1E9E63` | - |
| `warn` | `#F0B429` | `#A8710A` | - |
| `alert` | `#FF5D5D` | `#D33B4A` | - |
| `onSignal` | `#FFFFFF` | `#FFFFFF` | `.button-light { background: --blue; color: #fff }` |
| `onData` | `#05070B` | `#FFFFFF` | - |

**`signal` is the fill; `signalText` is the glyph.** `--blue` sits at 3.8:1 against
Blueprint's `ink` - fine for the WCAG 3:1 UI-component floor (indicators, borders,
focus rings, fills), below the 4.5:1 text floor. A *saturated* accent cannot be
both: dark enough to carry white `onSignal` content as a fill, and light enough to
read as a glyph on `ink`. Amber can do both, which is why the single-`signal`
assumption held until Blueprint.

So: **`signal` on a rect, `signalText` on a `Text` or `Icon`.** In Operator and
Clean the two are the same value; in Blueprint `signalText` is the site's own
link-blue (`#88A9FF`, 8.1:1), and in Daylight it is a darker amber (`#95580A`,
5.3:1) - which also retires a defect that predated Blueprint, where Daylight's
`signal` was 2.6:1 as text on its own near-white floor.

`BossThemesRegistryTest` holds every theme to both floors, on `ink`, `panel` **and**
`raised` - the dark themes' `panel`/`raised` are lighter than `ink`, so an ink-only
check reports better ratios than the surfaces the chrome actually paints on. It
also fails if `signalText` aliases `signal` in a theme where `signal` does not
already clear 4.5:1, so the token cannot quietly become decorative.

If a control reads as too quiet, thicken the indicator or lift the wash - do not
brighten `signal`.

### Surface & ink - Operator

These are the raw `BossPalette` constants - the Operator values, kept as the
reference definition of each slot's *role*. Read them for what a slot is for, not
for what color the app is currently painted; use `BossTheme.colors.*` for that.

| Token | Hex | Role |
|-------|-----|------|
| `ink` | `#0E1217` | Base floor - host **and** terminal |
| `panel` | `#161D26` | Chrome / card / sidebar |
| `raised` | `#1E2731` | Menus, popovers, hover |
| `line` | `#2A3744` | Hairline border / divider |
| `lineStrong` | `#3A4B5C` | Input edge / strong border |
| `chalk` | `#E9EEF3` | Primary text |
| `mist` | `#8593A3` | Secondary text |
| `muted` | `#5C6977` | Tertiary / disabled |

### Signals - Operator

| Token | Hex | Role |
|-------|-----|------|
| `signal` | `#F2A93B` | Amber - live / active / primary action |
| `signalDim` | `#C98A2E` | Pressed / variant |
| `signalWash` | `#2A2113` | Faint amber hover fill on `ink` |
| `signalText` | `#F2A93B` | Signal-colored **glyphs** (= `signal` here; amber clears 4.5:1) |
| `data` | `#56C7E0` | Cyan - links / info / data |
| `ok` | `#6FD08C` | Success / clean exit |
| `warn` | `#F0B429` | Warning |
| `alert` | `#F2685F` | Error / destructive |
| `onSignal` | `#1A1206` | Ink that sits on an amber fill |
| `onData` | `#06222A` | Ink that sits on a cyan fill |

### Terminal - "BOSS Operator" theme

| Property | Hex |
|----------|-----|
| foreground | `#D7DEE6` |
| background | `#0E1217` (shared `ink`) |
| cursor | `#F2A93B` (the signature) |
| cursorText | `#0E1217` |
| selection | `#21405A` |
| searchMatch | `#F0B429` |
| hyperlink | `#56C7E0` |

**ANSI 16** (tuned to sit calmly on `ink`):

| # | Color | Hex | | # | Bright | Hex |
|---|-------|-----|---|---|--------|-----|
| 0 | black | `#15202B` | | 8 | brightBlack | `#3A4B5C` |
| 1 | red | `#F2685F` | | 9 | brightRed | `#FF8A80` |
| 2 | green | `#6FD08C` | | 10 | brightGreen | `#8FE0A6` |
| 3 | yellow | `#F2A93B` | | 11 | brightYellow | `#FFC560` |
| 4 | blue | `#5C9FE0` | | 12 | brightBlue | `#82B7F0` |
| 5 | magenta | `#C792EA` | | 13 | brightMagenta | `#DDB0F5` |
| 6 | cyan | `#56C7E0` | | 14 | brightCyan | `#7FD9EE` |
| 7 | white | `#C7D1DB` | | 15 | brightWhite | `#E9EEF3` |

---

## 3. Typography

Monospace is the brand voice - display, eyebrows, labels, every number and path.
A humanist sans carries running UI copy where reading speed matters. The app
bundles **MesloLGS Nerd Font** for the mono role.

| Style | Family | Size | Weight | Tracking | Use |
|-------|--------|------|--------|----------|-----|
| `displayLarge` | mono | 28 | SemiBold | −0.5 | Hero headings |
| `displaySmall` | mono | 22 | SemiBold | - | Section headings |
| `title` | sans | 16 | SemiBold | - | Panel / dialog titles |
| `body` | sans | 13 | Normal | - | Running UI copy |
| `data` | mono | 14 | Normal | - | Terminal, code, paths, metrics |
| `label` | mono | 11 | SemiBold | +1.5, UPPER | Eyebrows / section labels |
| `micro` | mono | 10 | Medium | +1.0, UPPER | Captions / status |

---

## 4. Space, shape, motion

**Spacing** - 8.dp base, 4.dp half-step:
`hairline 2` · `xs 4` · `sm 8` · `md 12` · `lg 16` · `xl 24` · `xxl 32`.
`cellWidth 8.4` / `cellHeight 17` mirror the terminal char cell.

**Radius** (small radii read as a precision instrument):
`grid 0` (terminal) · `input 3` · `button 5` · `card 5` · `dialog 8`.

**Elevation** - tint first; shadow only for true popovers:
`floor` (ink) · `panel` (tint + 1px line) · `popover 8.dp` (menus / dialogs).

**Motion** - `instant 0ms` (cursor, key echo) · `fast 90ms` (hover, press) ·
`base 160ms` (menus, panels) · `cursorBlink 530ms`. Easing
`cubic-bezier(0.2, 0, 0, 1)`. Honor `prefers-reduced-motion` /
the OS reduce-motion setting: the cursor stops blinking, panels cut instead of slide.

---

## 5. Components

The active item always wears amber. A few canonical specs:

- **Tab** - selected tab shows a 4.dp bottom marker: `signal` when focused,
  `line` when not (the system's **signature** element).
- **Button** - primary = `signal` fill + `onSignal` text, used once per view;
  secondary = transparent + `lineStrong` border; ghost = transparent;
  destructive = `alert`, outlined until hover then committed.
- **Text field** - `ink` fill, `lineStrong` border, focus ring `signal`.
- **Context menu** - `raised` surface, `lineStrong` border, hover = `signalWash`.
- **Dialog** - `panel`, `dialog` radius, 24.dp padding, popover elevation.
- **Scrollbar** - thumb `#ffffff30` over a 12%-white track; search hits = amber
  markers; command-block status = `ok` / `alert` gutter markers.

---

## 6. Consuming the tokens (Compose)

Inside a `BossTheme { … }` scope, read tokens via the `BossTheme` accessor
object - the same pattern as `MaterialTheme.colors`:

```kotlin
import ai.rever.boss.plugin.ui.BossTheme

@Composable
fun Example() {
    val colors = BossTheme.colors
    val space = BossTheme.space
    val radii = BossTheme.radius

    Surface(color = colors.panel, shape = RoundedCornerShape(radii.card)) {
        Text("Live", color = colors.signal, modifier = Modifier.padding(space.md))
    }
}
```

### Worked example - `BossTabButton`

The selected-tab marker is the design system's signature, so it's the reference
migration (`composeApp/.../components/buttons/BossTabButton.kt`):

```kotlin
val colors = BossTheme.colors          // captured once at the top of the composable
// …
Box(
    modifier = Modifier
        .height(4.dp)
        .background(
            // amber signal when focused, quiet line when not
            color = if (isFocused) colors.signal else colors.line,
            shape = RoundedCornerShape(2.dp),
        ),
)
```

The same pattern is applied in `BossActionButton` (selected background →
`colors.signal`, tooltip → `colors.raised`), `ConfirmationDialog` (destructive →
`colors.alert`, warning → `colors.warn`, surface/radii via tokens), and
`ContextMenu` (item text → `colors.textPrimary`, arrows/indicators →
`colors.textSecondary`).

### Fonts

The mono brand voice is **wired to the bundled MesloLGS Nerd Font**: the host's
`BossTheme` re-export (`composeApp/.../components/misc/BossTheme.kt`) builds a
`FontFamily` from `Res.font.meslolgs_nf_*` and injects it via
`bossTypography(mono = …)`, so all four theme roots (`BossApp`, `main`,
`AuthScreenContainer`, `SettingsWindow`) render `BossTheme.type.*` in the real
face. Outside the host (e.g. plugins) the type scale falls back to the platform
generic monospace. The sans role is the platform default - bundle Inter and pass
`bossTypography(mono = …, sans = …)` to brand it further.

### Migration note

`BossColors` (the legacy flat object and its top-level aliases like
`BossDarkAccent`) now **delegate to `BossPalette`** with names unchanged - so
existing components re-skin automatically with zero edits. New and touched code
should prefer the semantic `BossTheme.colors.*` accessors. Spacing, radius,
elevation, and motion tokens are consumed by the migrated components above;
migrate the rest opportunistically as you touch them. Watch the accessor names:
the semantic scheme uses `textPrimary` / `textSecondary` / `textMuted`, not the
raw-palette names `chalk` / `mist` / `muted`.

---

## Where it lives

| Concern | Location |
|---------|----------|
| Host tokens (source of truth) | `plugin-platform/plugin-ui-core/src/commonMain/kotlin/ai/rever/boss/plugin/ui/BossDesignSystem.kt` |
| Selectable themes + reactive controller | `…/ui/BossThemes.kt` (`BossThemes.all`, `BossThemeController`) |
| Theme picker UI | `composeApp/.../components/settings/sections/ThemeSettings.kt` (renders `BossThemes.all`) |
| Persisted choice | `composeApp/.../theme/AppThemeSettings.kt` → `~/.boss/app-theme-settings.json` |
| Legacy color object (delegates to `BossPalette`) | `…/ui/BossColors.kt` |
| `BossTheme()` composable (provides token locals) | `…/ui/BossTheme.kt` |
| composeApp re-exports + MesloLGS font injection | `composeApp/.../components/misc/BossTheme.kt` |
| Terminal theme + ANSI palette | BossTerm `…/settings/theme/BuiltinThemes.kt`, `BuiltinColorPalettes.kt` (`boss-blueprint` is the default, `boss-operator` alongside it) |
| Terminal defaults | BossTerm `…/settings/TerminalSettings.kt` (`activeThemeId = "boss-blueprint"`, and the fg/bg/selection defaults must match that theme) |
| Terminal chrome tokens | BossTerm `…/settings/theme/UiTheme.kt` (`EXACT_TOKENS` - both BOSS identities skip derivation) |

**Defaults:** the host default is `nvidia` (`BossThemes.UNIX_DEFAULT_ID`) on macOS
and Linux and `blueprint-light` (`BossThemes.WINDOWS_DEFAULT_ID`) on Windows,
resolved through `BossThemes.defaultIdFor(isWindows)`. `BossThemes.DEFAULT_ID` is
still `blueprint` and is now the class default only. The BossTerm default is
`boss-blueprint` (`DEFAULT_THEME_ID` / `TerminalSettings.activeThemeId`) on every
platform, but that default is what standalone BossTerm opens with: inside BOSS the
`terminal-tab` plugin's `ApplyHostThemeToTerminal()` pushes the host tokens into
BossTerm's `ThemeManager` on every compose, so the terminal follows the host and a
Windows first run comes up light throughout. Existing saved settings keep their
current theme; only fresh installs pick up a new default.

There is no `boss-nvidia` BossTerm builtin, and none is needed. The bridge's
builtin match is restricted to `boss-` prefixed themes, so NVIDIA's pure-black
`ink` does not collide with BossTerm's XTerm-derived `default` (also `#000000`);
with no BOSS builtin at that floor the bridge synthesizes the terminal from the
host tokens instead, which is what makes the shell come up green-on-black.

**Four mirrors, one settings file.** The palettes exist in four places and they
have to agree:

| Mirror | Where |
|--------|-------|
| Host (source of truth) | `…/ui/BossThemes.kt` |
| Terminal grid + chrome | BossTerm `…/settings/theme/{BuiltinThemes,BuiltinColorPalettes,UiTheme}.kt` |
| Out-of-process plugins | `modules/boss-ui-sdk/.../WidgetSemantics.kt` (`ThemeToken`, names only) |
| Rust host | `BossConsoleRust/crates/boss-core/src/theme.rs` |

The Rust mirror reads and writes **the same** `~/.boss/app-theme-settings.json`,
and a file that omits `appThemeId` means "the default" - so its `ThemeId::DEFAULT`
must move whenever `BossThemes.DEFAULT_ID` does, or the two hosts render different
themes from identical settings.

`DEFAULT_ID` has not moved, so that mirror is still in step. The **Windows**
default is a second thing to mirror: until `boss-core` grows the equivalent of
`defaultIdFor(isWindows)`, a Windows first run opens light under this host and
dark under the Rust one. Neither ships on Windows today, so this is a note for
whoever gets there first, not a live divergence.
