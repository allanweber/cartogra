---
name: Cartogra Core
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#494454'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#7b7486'
  outline-variant: '#cbc3d7'
  surface-tint: '#6d3bd7'
  primary: '#6b38d4'
  on-primary: '#ffffff'
  primary-container: '#8455ef'
  on-primary-container: '#fffbff'
  inverse-primary: '#d0bcff'
  secondary: '#4648d4'
  on-secondary: '#ffffff'
  secondary-container: '#6063ee'
  on-secondary-container: '#fffbff'
  tertiary: '#00628d'
  on-tertiary: '#ffffff'
  tertiary-container: '#007cb1'
  on-tertiary-container: '#fcfcff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#e9ddff'
  primary-fixed-dim: '#d0bcff'
  on-primary-fixed: '#23005c'
  on-primary-fixed-variant: '#5516be'
  secondary-fixed: '#e1e0ff'
  secondary-fixed-dim: '#c0c1ff'
  on-secondary-fixed: '#07006c'
  on-secondary-fixed-variant: '#2f2ebe'
  tertiary-fixed: '#c9e6ff'
  tertiary-fixed-dim: '#89ceff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004c6e'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
  border-default: '#e2e8f0'
  success: '#16a34a'
  on-success: '#ffffff'
  success-container: '#dcfce7'
  warning: '#d97706'
  on-warning: '#ffffff'
  warning-container: '#fef3c7'
  critical: '#dc2626'
  on-critical: '#ffffff'
  critical-container: '#fee2e2'
typography:
  display:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
    letterSpacing: -0.02em
  h1:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.015em
  h2:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.01em
  h3:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.01em
  body:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
  body-sm:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: 0em
  label:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.02em
  mono:
    fontFamily: "'JetBrains Mono', 'Fira Code', ui-monospace, monospace"
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.25rem
  2xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  2xl: 48px
  gutter: 20px
  margin: 24px
elevation:
  '0': 'none'
  '1': '0 0 0 1px #e2e8f0'
  '2': '0 4px 24px 0 rgba(11,28,48,0.06), 0 1px 4px 0 rgba(11,28,48,0.04)'
  '3': '0 8px 40px 0 rgba(11,28,48,0.10), 0 2px 8px 0 rgba(11,28,48,0.06)'
---

## Brand & Style

This design system is built for high-performance service intelligence, prioritizing clarity, speed, and analytical depth. It targets technical operators and engineers who require a "heads-up display" for complex infrastructure.

The aesthetic follows a **Modern DevTool** approach—a hybrid of Minimalism and Corporate Modernity. It draws inspiration from the precision of Linear and the airy, typography-first philosophy of Vercel. The interface evokes a sense of "quiet intelligence," using generous whitespace to reduce cognitive load while maintaining enough density for professional data density. The emotional response should be one of complete control and professional-grade reliability.

## Colors

The palette is anchored by a vibrant Purple accent, signifying the "intelligence" layer of the platform. The foundation relies on a sophisticated range of Slate grays to create a neutral workspace that lets data visualizations and status indicators stand out.

Health status colors are non-negotiable and follow industry standards for immediate recognition:

- **Success:** A vibrant emerald (`success: #16a34a`), used for healthy nodes and resolved incidents.
- **Warning:** A warm amber (`warning: #d97706`), indicating latency or non-critical threshold breaches.
- **Critical:** A sharp, urgent red (`critical: #dc2626`) for outages and system failures.

Surface colors utilize a "Layered White" strategy, using subtle shifts in gray (#F8FAFC) to differentiate sidebars and navigation from the primary workspace canvas.

## Typography

This design system uses **Inter** for all UI text and **JetBrains Mono** (fallback: Fira Code, ui-monospace) for code, IDs, IP addresses, log output, and any data view where column alignment matters. The hierarchy is tight, with small increments between sizes to accommodate dense data views.

- **Headlines:** Use tighter letter spacing and semi-bold weights to create a strong visual anchor.
- **Body Text:** Optimized at 14px for the primary interface to balance readability and information density.
- **Monospaced Data:** Use the `mono` token family (JetBrains Mono) for IDs, hashes, ports, and log lines — never Inter for these cases.
- **Labels:** Uppercase is discouraged; use medium weight and slight letter spacing for secondary metadata.

## Layout & Spacing

The layout philosophy follows a **Fluid Grid** model with a 12-column structure, allowing the dashboard to scale from laptop screens to large monitoring displays.

A strict 4px/8px baseline grid ensures vertical rhythm. We use a "Balanced Density" approach:

- **Margins:** 24px container margins keep content from feeling cramped against the viewport edges.
- **Gutters:** 20px gutters provide clear separation between cards and widgets without excessive "dead air."
- **In-component Spacing:** Components use 8px (sm) or 12px (md-internal) padding to maintain a compact but touch-friendly feel.

## Elevation & Depth

This design system uses a combination of **Tonal Layers** and **Ambient Shadows** to create a structured hierarchy. Depth is used sparingly to signify interactivity and importance.

- **Level 0 (Canvas):** The base background (#FFFFFF).
- **Level 1 (Cards/Sidebar):** Slightly elevated using a 1px border (#E2E8F0). No shadow in default state.
- **Level 2 (Dropdowns/Modals):** These use soft, multi-layered shadows. The shadows should have a large blur radius (12px - 24px) with very low opacity (4-8%) and a slight neutral-blue tint to feel "airier."
- **Level 3 (Command Menus):** A combination of a subtle backdrop blur (8px) and a prominent shadow to focus the user's attention on the center-screen task. Apply the `elevation-3` shadow token **plus** the `backdrop-blur-sm` Tailwind class — the blur cannot be expressed as a shadow value.

Borders are the primary method of separation, with shadows acting only as a secondary hint for floating elements.

## Shapes

The shape language focuses on a "Soft Tech" feel. While the system is professional, the use of larger radii prevents it from feeling cold or dated.

- **Standard Elements:** Buttons, inputs, and small chips use `rounded-lg` (1rem) as the default.
- **Containers:** Dashboard cards and main content areas use `rounded-2xl` (1.5rem) to create a distinct, modern framing effect.
- **Health Badges:** These are "squircle" inspired, using the standard `rounded-lg` rather than full pills to maintain the DevTool aesthetic.

## Components

### Health Status Badges

These are critical for the platform. They should be styled as subtle "Soft" badges:

- **Success:** `success-container` background with `success` text and a 2px dot icon to the left.
- **Warning:** `warning-container` background with `warning` text.
- **Critical:** `critical-container` background with `critical` text. For active critical alerts, a subtle pulse animation on the dot icon is recommended.

### Buttons

- **Primary:** `primary` background (`#6b38d4`) with `on-primary` text. High-contrast, `rounded-lg` corner radius.
- **Secondary:** `surface-container-lowest` background with a 1px `border-default` border.
- **Ghost:** No background or border until hover; used for low-priority actions in tables.

### Inputs

DevTool-style inputs should have a 1px `border-default` border. On focus, the border transitions to `primary` with a 3px soft outer glow (ring).

### Cards

Cards are the primary container for service metrics. They feature a 1px `border-default` border, `rounded-2xl` corner radius, and `elevation-1` shadow. Titles should be H3 (16px semi-bold) with a 12px spacing from the content.

### Additional Components

- **Code Blocks:** Dark-themed syntax highlighting even in Light Mode for clarity. Use the `mono` font token.
- **Command Palette (CMD+K):** A centered, floating modal with a search input and filtered list, utilizing `elevation-3` shadow plus `backdrop-blur-sm`.
- **Service Graph Nodes:** Circular or highly rounded shapes representing microservices, color-coded by health status (`success`, `warning`, `critical`).
