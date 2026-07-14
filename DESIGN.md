---
name: Cartogra
description: Living service registry and dependency intelligence platform for engineering teams
colors:
  signal-blue: "oklch(0.50 0.20 218)"
  signal-blue-muted: "oklch(0.55 0.14 218)"
  instrument-white: "oklch(0.985 0.006 220)"
  deep-slate-ink: "oklch(0.215 0.030 220)"
  pure-canvas: "oklch(1 0.003 220)"
  cool-mist: "oklch(0.945 0.012 220)"
  pale-steel: "oklch(0.930 0.020 220)"
  hairline-steel: "oklch(0.904 0.010 220)"
  panel-mist: "oklch(0.960 0.012 220)"
  form-alert-red: "oklch(0.577 0.233 28)"
  critical-red: "oklch(0.52 0.20 28)"
  critical-red-subtle: "oklch(0.96 0.04 28)"
  caution-amber: "oklch(0.50 0.13 65)"
  caution-amber-subtle: "oklch(0.96 0.05 75)"
  healthy-green: "oklch(0.48 0.15 145)"
  healthy-green-subtle: "oklch(0.96 0.04 145)"
  info-indigo: "oklch(0.52 0.15 245)"
  info-indigo-subtle: "oklch(0.96 0.04 245)"
typography:
  display:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 500
    lineHeight: 1.375
    letterSpacing: "normal"
  body:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.43
    letterSpacing: "normal"
  label:
    fontFamily: "Geist Variable, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 500
    lineHeight: 1
    letterSpacing: "normal"
  mono:
    fontFamily: "JetBrains Mono, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1.33
    letterSpacing: "normal"
rounded:
  sm: "9.6px"
  md: "12.8px"
  lg: "16px"
  xl: "22.4px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
components:
  button-primary:
    backgroundColor: "{colors.signal-blue}"
    textColor: "{colors.instrument-white}"
    rounded: "{rounded.lg}"
    padding: "0 10px"
    height: "32px"
  button-primary-hover:
    backgroundColor: "{colors.signal-blue}"
  button-outline:
    backgroundColor: "{colors.pure-canvas}"
    textColor: "{colors.deep-slate-ink}"
    rounded: "{rounded.lg}"
  badge-default:
    backgroundColor: "{colors.signal-blue}"
    textColor: "{colors.instrument-white}"
    rounded: "{rounded.full}"
    height: "20px"
  card:
    backgroundColor: "{colors.pure-canvas}"
    rounded: "{rounded.xl}"
    padding: "16px"
---

# Design System: Cartogra

## 1. Overview

### Creative North Star: "The Control Tower"

Cartogra puts an operator in front of a live system, not a marketing surface. The visual language is built for someone who needs to answer "is this safe to ship" in seconds: a single UI family (Geist Variable, used for headings, body, and labels alike — no display/body pairing), a restrained blue accent reserved for action and selection, and a flat, ring-bordered surface system that reads as instrument-panel calm rather than dashboard clutter. Depth appears only where it's functional — floating layers (menus, dialogs) lift off the canvas with a soft shadow; everything at rest stays flat, separated by hairline rings instead of shadows.

This system explicitly rejects the three anti-references named in PRODUCT.md: it does not sugarcoat complexity with a gimmicky, overly-friendly tone; it is not a generic, checkbox-driven enterprise dashboard; and it does not spend visual budget on decoration that doesn't change a decision. Status color (critical / warning / success / info) is the one place saturation is allowed to run high, because status is the one thing that must be recognized instantly and without ambiguity.

**Key Characteristics:**

- One typeface family (Geist Variable) across every text role; JetBrains Mono reserved strictly for IDs, hashes, and machine-generated data.
- Restrained color: neutral cool-blue surfaces, one signal-blue accent for actions and selection, four non-negotiable status hues for health/severity.
- Flat-at-rest, ring-bordered surfaces; shadow appears only on floating/overlay layers (dropdowns, dialogs, sheets).
- Generous corner radii (16–22px) on containers read as considered rather than sharp, without tipping into playful.

## 2. Colors

The palette is a single cool blue-gray neutral ramp (hue ~220) carrying one signal-blue accent (hue 218) and a four-color status vocabulary that intentionally sits on different hues from the accent so status is never confused with "selected" or "primary action."

### Primary

- **Signal Blue** (`oklch(0.50 0.20 218)`): the one accent color in the system. Used only for primary actions, current selection, active nav state, links, and focus rings — never as decoration. Tuned to clear WCAG AA 4.5:1 as body-size text against Instrument White (4.75:1). On dark surfaces it lifts to `oklch(0.72 0.16 218)` to hold contrast without changing hue.

### Neutral

- **Instrument White** (`oklch(0.985 0.006 220)`): app background.
- **Pure Canvas** (`oklch(1 0.003 220)`): card and popover surfaces — one step brighter than the app background so containers read as lifted without a shadow.
- **Panel Mist** (`oklch(0.960 0.012 220)`): the second neutral layer for the sidebar, per the product register's "second neutral layer for sidebars/toolbars" rule — cooler than the content canvas.
- **Cool Mist** (`oklch(0.945 0.012 220)`): muted backgrounds (disabled states, table stripe, footer bands).
- **Pale Steel** (`oklch(0.930 0.020 220)`): accent-neutral hover backgrounds.
- **Hairline Steel** (`oklch(0.904 0.010 220)`): all borders and dividers — the primary method of separation in this system.
- **Deep Slate Ink** (`oklch(0.215 0.030 220)`): body text and headings.

### Status (non-negotiable, color-blind-safe pairing with icon/shape — never color alone)

- **Healthy Green** (`oklch(0.48 0.15 145)`) / subtle wash `oklch(0.96 0.04 145)`: healthy services, compatible contracts, resolved risk.
- **Caution Amber** (`oklch(0.50 0.13 65)`) / subtle wash `oklch(0.96 0.05 75)`: degraded health, non-breaking contract warnings, stale-but-not-broken states.
- **Critical Red** (`oklch(0.52 0.20 28)`) / subtle wash `oklch(0.96 0.04 28)`: down services, breaking contract changes, unresolved high-severity risk.
- **Info Indigo** (`oklch(0.52 0.15 245)`) / subtle wash `oklch(0.96 0.04 245)`: neutral informational callouts that aren't a health state — deliberately a different hue from Signal Blue so it never reads as "clickable."
- **Form Alert Red** (`oklch(0.577 0.233 28)`): a separate, slightly more saturated red reserved for form/input validation errors (`destructive` token) — kept distinct from Critical Red so a broken form field is never visually confused with a down service.

### Named Rules: Colors

**The Single Source Rule.** Every status color in the codebase — health badges, severity dots, contract states — resolves through exactly four CSS custom properties (`--critical`, `--warning`, `--success`, `--info`, each with a `-subtle` wash). No component defines its own status oklch literal.

**The Rarity Rule.** Signal Blue appears on primary buttons, active selection, links, and focus rings only. If an element isn't one of those four things, it doesn't get the accent color.

## 3. Typography

**UI Font:** Geist Variable (fallback: sans-serif)
**Mono Font:** JetBrains Mono (fallback: monospace)

**Character:** One geometric-humanist sans carries every role from a 24px section heading down to a 12px label — deliberate, per the product register's "one family is often right" guidance. The scale steps are tight (1.125–1.2 ratio) to keep dense data views quiet rather than shouting a hierarchy that dense screens don't need.

### Hierarchy

- **Display** (500, 24px/1.375, normal tracking): card and section titles (`font-heading`, `text-base`/`text-2xl` range) — the largest text in the product; there is no marketing-scale hero type.
- **Body** (400, 14px/1.43): the default UI text size for labels, table cells, descriptions, form values.
- **Label** (500, 12px/1): metadata, badge text, uppercase-discouraged secondary text — medium weight and default tracking carry the emphasis instead of letter-spacing tricks.
- **Mono** (400, 12px/1.33): service IDs, hashes, ports, trace IDs, log lines, generated SQL — never Geist for these.

### Named Rules: Typography

**The One-Voice Rule.** No display/body font pairing. Geist Variable is the UI font for every text role; JetBrains Mono is the only second family, and it is reserved strictly for machine-readable data, not for stylistic contrast.

## 4. Elevation

Cartogra is flat-at-rest. Resting surfaces (cards, inputs, the app shell) are separated by a 1px `ring-foreground/10` hairline, not a shadow — depth is implied by tone and border, not lift. Shadow is reserved for layers that are genuinely floating above the page: dropdown menus, dialogs, and sheets combine the same hairline ring with a soft shadow, so the shadow reads as "this is temporarily above everything else," not as generic card polish.

### Shadow Vocabulary

- **Resting surface** (`ring-1 ring-foreground/10`, no shadow): cards, inputs, the sidebar, the app shell.
- **Floating layer — menu** (`shadow-md` + `ring-1 ring-foreground/10`): dropdown menus, context menus.
- **Floating layer — modal** (`shadow-lg` + `ring-1 ring-foreground/10`): dialogs, sheets, command palette.

### Named Rules: Elevation

**The Flat-By-Default Rule.** If it's not floating above the page (menu, dialog, sheet, toast), it does not get a `box-shadow`. Depth at rest is a hairline ring, never a shadow — this keeps the dense, always-visible surfaces (cards, tables) calm instead of looking like a stack of separately-lit panels.

## 5. Components

Every interactive component ships default, hover, focus-visible, active, disabled, and (where applicable) loading and aria-invalid states — the product register's baseline, not an aspiration.

### Buttons

- **Shape:** `rounded-lg` (16px) at default/sm/lg sizes; the `xs` and icon-`xs`/`sm` sizes clamp to a smaller radius (`min(12.8px, 10-12px)`) so tiny controls don't look over-rounded relative to their size.
- **Primary:** Signal Blue background, white text, 32px height (`h-8`) at default size. Active state nudges the button down 1px (`translate-y-px`) instead of scaling — a physical "pressed" cue with no bounce.
- **Outline / Secondary / Ghost / Destructive / Link:** outline uses a hairline border on the canvas color; secondary uses the muted-blue secondary token; ghost is borderless until hover; destructive uses a 10%-opacity red wash (not a solid red fill) so it reads as "dangerous" without shouting; link is text-only with underline on hover.
- **Touch target:** every size variant grows to a 44px hit box under `pointer-coarse:` (touch) while staying at its dense desktop size under `pointer-fine:` (mouse/trackpad) — density and touch accessibility aren't a tradeoff here, they're resolved per input device.
- **Focus:** a 3px `ring-ring/50` halo plus a solid ring border — visible without relying on color alone.

### Badges

- **Shape:** `rounded-4xl` (effectively a full pill at 20px height) — the one place in the system that uses a true pill rather than the standard corner-radius scale, reserved for compact status/count labels.
- **Variants:** default (solid Signal Blue), secondary, destructive (red wash, matching the button pattern), outline (hairline border), ghost, link.
- **Severity badges:** outline variant with `border-current` + a severity color class (`severity-critical` / `severity-warning` / `severity-info`) — text and border share the status color, background stays neutral. Never a colored side-stripe.

### Cards

- **Corner Style:** `rounded-xl` (22.4px) — the most generous radius in the system, reserved for top-level containers so they read as distinct "rooms" on the page.
- **Background:** Pure Canvas, one step brighter than the Instrument White app background.
- **Shadow Strategy:** none at rest — see Elevation. A 1px `ring-foreground/10` is the only separation.
- **Internal Padding:** 16px (`py-4`, header/content `px-4`); a `sm` density variant drops to 12px for compact contexts.

### Inputs

- **Style:** `rounded-lg` (16px), hairline `border-input`, transparent background, 32px height (`h-8`).
- **Focus:** border shifts to Signal Blue plus a 3px `ring-ring/50` soft outer glow — identical focus language to buttons, so the "this is now active" cue is consistent across every control type.
- **Error:** border and ring shift to Form Alert Red (`aria-invalid`) — a distinct red from Critical Red, so a bad form field never reads as "this service is down."
- **Disabled:** 50% opacity, muted background wash.

### Navigation

- Sidebar uses the Panel Mist neutral layer (cooler than the content canvas) to separate persistent chrome from the task surface, per the product register's standard top-bar/side-nav pattern. Active nav state uses the same Signal Blue + hairline-ring language as every other "selected" state in the system.

### Floating layers (dropdown / dialog / command palette)

Dropdown menus and dialogs are Pure Canvas surfaces with `rounded-lg` corners, `ring-1 ring-foreground/10`, and a shadow (`shadow-md` menus, `shadow-lg` dialogs/sheets) — the only components in the system that combine a ring with a shadow. Sheets slide in from an edge with a 200ms ease transition; dialogs fade/zoom from 95%. All floating-layer motion respects `prefers-reduced-motion` globally (animation and transition durations collapse to near-zero).

## 6. Do's and Don'ts

### Do

- **Do** keep every text role in Geist Variable; reach for JetBrains Mono only for IDs, hashes, ports, and log/trace output.
- **Do** resolve every health/severity color through the four semantic tokens (`--critical`, `--warning`, `--success`, `--info`) and their `-subtle` washes — never a component-local `oklch()` literal.
- **Do** pair every status color with a shape or icon (dot, badge, icon), never color alone — PRODUCT.md's "color-blind-safe status signaling" requirement.
- **Do** keep resting surfaces flat (ring only); reserve shadow for dropdowns, dialogs, and sheets.
- **Do** use the same focus treatment (Signal Blue border + 3px ring) on every interactive control — buttons, inputs, and links share one focus language.
- **Do** size every interactive element for a 44px touch target on coarse-pointer devices while keeping dense desktop sizing on fine-pointer devices.

### Don't

- **Don't** add a colored `border-left`/`border-right` stripe to cards, list rows, or alerts as a severity accent — severity reads from a badge and a status dot, never row chrome. This is an absolute ban, not a style preference.
- **Don't** introduce a second UI typeface for "visual interest" — PRODUCT.md's anti-reference list explicitly rejects decorative flourishes that don't change a decision, and a second display font is exactly that.
- **Don't** add a drop shadow to a resting card, table, or panel — shadow is reserved for genuinely floating layers.
- **Don't** use Signal Blue for anything other than primary actions, selection, active nav state, links, and focus rings. It is not a decorative accent.
- **Don't** sugarcoat empty/error states with cute illustrations or gimmicky copy — PRODUCT.md rejects "overly friendly developer tools that sugarcoat complexity with gimmicky tone." Empty states teach the interface in plain, direct language.
- **Don't** ship a checkbox-driven, bloated settings screen — PRODUCT.md rejects "generic enterprise dashboards that feel slow, bloated, and checkbox-driven." Favor progressive disclosure over a modal-first, form-heavy pattern.
- **Don't** reach for a modal as the first solution — exhaust inline and progressive alternatives first, per the product register's "modal as first thought is usually laziness."
