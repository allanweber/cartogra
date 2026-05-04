# Product

## Register

Cartogra — living service registry and dependency intelligence platform for engineering teams operating multi-service environments. Surfaces real-time service ownership, dependency risk, contract health, and architectural anti-patterns so high-stakes technical decisions are made in seconds, not hours.

## Users

Primary users are software engineers, staff/principal engineers, platform teams, and engineering leaders working in multi-service environments.

They use Cartogra during delivery, incident response, and architecture planning when they need fast confidence about risk, ownership, and downstream impact.

## Product Purpose

Cartogra exists to compress decision time for high-risk system questions: whether a change is safe, whether a service can be removed, who owns affected systems, and where architectural risk is concentrated.

Success means teams make these decisions directly in Cartogra without stitching context across multiple tools, catching contract breaks before deploy, reducing ownership ambiguity, and improving time-to-answer from minutes or hours to seconds.

## Brand Personality

Insightful, decisive, uncompromising.

The tone should be clear and direct, never vague, and focused on actionable confidence for technical decisions under pressure.

## Anti-references

- Overly friendly developer tools that sugarcoat complexity with gimmicky tone.
- Generic enterprise dashboards that feel slow, bloated, and checkbox-driven.
- Decorative visuals that look impressive but do not change decisions.

## Design Principles

1. Decision-first over dashboard-first: every surface should help users reach a concrete technical decision quickly.
2. Evidence before opinion: claims about impact, health, and risk must be anchored in observable data and traceable context.
3. Operational speed with calm control: prioritize rapid scanning and triage without visual noise or panic styling.
4. Ownership clarity as a feature: always make responsibility and affected teams obvious at point of use.
5. Workflow-native integration: outputs should plug into PR review, incident response, and architecture discussions, not live in isolation.

## Accessibility & Inclusion

- WCAG AA baseline for all interfaces.
- Full keyboard-first navigation for core workflows.
- Reduced-motion support for all non-essential animations.
- Color-blind-safe status signaling that does not rely on color alone.
- Maintain clear contrast and readable hierarchy for dense data views.

## Screen Inventory

### Phase 0 — Foundation

| Screen | Route | Purpose |
|--------|-------|---------|
| App Shell | `_layout.tsx` (all routes) | Root layout: sidebar navigation, header, error boundary |
| 404 | `*` | Not-found fallback |

### Phase 1 — Auth + Registry

| Screen | Route | Purpose |
|--------|-------|---------|
| Login | `/login` | Email/password and OAuth sign-in |
| Register | `/register` | Account creation with email OTP verification flow |
| Verify Email | `/verify-email` | OTP entry and confirmation |
| Forgot Password | `/forgot-password` | Password reset initiation |
| OAuth Handoff | `/auth/callback` | OAuth provider callback and session creation |
| Catalog List | `/catalog` | Service list with filters, health badges, orphan highlights |
| Catalog Detail | `/catalog/$serviceId` | Service profile: dependencies, contracts, history, ownership |
| SCM Connections | `/settings/connections` | Connect and manage GitHub / Azure DevOps integrations |

### Phase 2 — Topology

| Screen | Route | Purpose |
|--------|-------|---------|
| Dependency Graph | `/graph` | D3 force graph: zoom, pan, declared/observed toggle, drift overlays |
| Impact Panel | `/graph` (panel) | Blast radius highlight, upstream/downstream detail, SPOF and cycle warnings |

### Phase 3 — Contract Guardian

| Screen | Route | Purpose |
|--------|-------|---------|
| Contract Hub | `/contracts` | Contract list; breaking-change queue with status badges |
| Diff Viewer | `/contracts/$contractId/diff` | Side-by-side spec diff with required/added/removed highlights |
| Compatibility Matrix | `/contracts/matrix` | Producer × consumer heatmap: green/yellow/red compatibility |
| Version Timeline | `/contracts/$contractId/history` | Chronological spec evolution for a single contract |
| CI Check Detail | `/contracts/checks/$checkId` | Full output and resolution controls for a single check run |

### Phase 4 — Intelligence

| Screen | Route | Purpose |
|--------|-------|---------|
| Intelligence Panel | `/intelligence` | NL query input, answer rendering, optional generated-SQL disclosure |
| Anti-Pattern Feed | `/intelligence` (section) | Severity-ranked findings with acknowledge/resolve actions |
| Health Score | `/intelligence` (section) | Health score trend chart and latest digest summary |
| Digest | `/intelligence/digest` | Full weekly digest with drill-down recommendations |

### Phase 5 — Production

| Screen | Route | Purpose |
|--------|-------|---------|
| Operations View | `/ops` | Connector health, recent platform events, Kafka lag, observability links |
| Settings / Admin | `/settings` | Tenant config, API key management, notification rules, team admin |
