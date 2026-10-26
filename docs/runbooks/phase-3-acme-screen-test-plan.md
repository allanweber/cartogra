# Phase 3 — Acme screen test plan

Manual pass over every screen Phase 3 created or changed, on the Acme Fintech tenant. Complements `pnpm acme verify`
(API-level) and the e2e specs (`frontend/e2e/drift.spec.ts`, `unresolved-span-services.spec.ts`): this plan checks what
a person sees. Tick each box; note the trace id of any error.

## 0. Setup

- [ ] Stack up per `seed/acme-fintech/README.md` §0–4 (gateway :8080, registry, topology, ingestion, collector, kind cluster, 21 pods Running).
- [ ] Topology started with a short drift window: `TOPOLOGY_DRIFT_SWEEP_INTERVAL=PT15S TOPOLOGY_DRIFT_MISSING_AFTER=PT2M`.
- [ ] `cd seed/acme-fintech && pnpm acme load` (second run reports 0 created).
- [ ] Start from an untouched tenant. A previous pass leaves state behind that `load` doesn't undo, and §1, §4 and §6 expect none of it:
  - `stripe-gateway` must **not** be in the catalog (6.3 registers it). If it is, delete it via the API (the UI has no delete): open it in the catalog, take the id from `/catalog/<id>`, then in the logged-in browser console run `fetch('/api/v1/registry/services/<id>', { method: 'DELETE' })`.
  - The 3 undeclared drifts must be open, not accepted, removed or dismissed (§4 resolves them).
- [ ] Wait ~60 s for spans, then `pnpm acme drift on`.
- [ ] Frontend `cd frontend && pnpm dev` → `http://localhost:3006`, log in as `acme@mail.com`.
- [ ] Check desktop (≥1280 px) **and** mobile (390 px, DevTools device mode) for every section below.

## 1. Graph — edge modes (`/graph`)

| # | Step | Expected |
|---|---|---|
| 1.1 | Open `/graph` | Defaults to **Declared**. 21 nodes; 43 declared edges; 2 cycle badges; `ledger-service` SPOF badge |
| 1.2 | Click **All** | URL `?type=ALL`. Declared + observed edges. `payments-service → notification-service` and `→ loyalty-service` present (http + kafka). `ledger-service → ledger-db` (db) present |
| 1.3 | Click **Observed** | URL `?type=OBSERVED`. Observed edges only. No "No observed dependencies yet" banner (spans flowing). `payments-service → stripe-gateway` is **not** an edge while `stripe-gateway` is unregistered; it is listed under Unresolved services instead. After 6.3 registers it, the edge appears (expected) |
| 1.4 | Click **Drift** | URL `?type=DRIFT`. Only drifted pairs drawn: 3 undeclared (`reporting-service → payments-service`, `webhook-relay → fraud-service`, `legacy-batch → notification-service`) and, after the 2 min window, 1 missing (`loyalty-service → fx-service`) |
| 1.5 | Drift with nothing drifting (before `drift on`, or after resolving all) | Drift mode shows "No drift. Declared and observed dependencies agree." |
| 1.6 | Reload on `?type=DRIFT` | Mode survives reload |
| 1.7 | Team filter = Ledger, in Drift mode | Only Ledger nodes; drift edges between them only |
| 1.8 | Type `ledger` in **Filter services by name** (Declared) | URL `?q=ledger`. Ledger services plus their direct callers/callees; only edges touching a match. Reload keeps the filter |
| 1.9 | With a service selected, type a name that doesn't match it | Selected service stays drawn, inspector stays open |
| 1.10 | Type `zzz` | "No services match “zzz”." — not the empty-tenant state |
| 1.11 | Select `payments-service` → Blast Radius tab | Each row shows connection badges. Upstream: `notification-service` shows `HTTP` and `KAFKA`; `loyalty-service` shows `KAFKA`. Downstream, after `drift on`: `reporting-service` shows dashed `HTTP · observed` (undeclared). Hover a badge → e.g. "HTTP declared and observed" |
| 1.12 | Select `ledger-service` → Blast Radius tab | `ledger-db` upstream row shows `DB` |

## 2. Graph — drift overlay and legend

| # | Step | Expected |
|---|---|---|
| 2.1 | Any mode with drift present | Legend next to the mode toggle: **Undeclared** (solid amber line), **Missing** (dashed blue line) |
| 2.2 | All mode | Undeclared edges solid amber, thicker than normal; missing edge dashed blue |
| 2.3 | Toggle OS dark mode | Amber/blue still readable against dark canvas; legend matches |
| 2.4 | Hover a drift edge | Tooltip shows protocol |
| 2.5 | Colour-blind check (DevTools → Rendering → emulate deuteranopia) | Undeclared vs missing still distinguishable by dash pattern |

## 3. Graph — side panel with no service selected

| # | Step | Expected |
|---|---|---|
| 3.1 | No node selected | Hint "Select a service to see what breaks if it goes down." then **Drift (n)** section and **Unresolved services (n)** section |
| 3.2 | Drift list rows | `source → target`, badge Undeclared/Missing, hint text. Undeclared rows offer **Accept** + **Dismiss**; missing row offers **Remove declared** + **Dismiss** |
| 3.3 | Click a drift row (in Declared mode) for an undeclared drift | Mode switches to **Drift**; row highlighted; its edge drawn thicker (4 px) |
| 3.4 | Click the same row again | Highlight cleared |
| 3.5 | Mobile 390 px | Panel stacks below the graph; rows readable, buttons tappable, no horizontal scroll |

## 4. Drift resolution

| # | Step | Expected |
|---|---|---|
| 4.1 | **Accept** on `reporting-service → payments-service` | Dialog "Declare this dependency?" names both services; confirm button is **primary (not red)** |
| 4.2 | Cancel | Nothing changes |
| 4.3 | Accept → confirm | Row disappears; edge now declared (visible in Declared mode, no longer amber); Risks/graph refresh |
| 4.4 | **Remove declared** on `loyalty-service → fx-service` | Red confirm button; after confirm, edge gone from Declared mode; drift row gone |
| 4.5 | **Dismiss** on `webhook-relay → fraud-service` | Red confirm; copy says the dependency itself is not changed; row gone and never returns on later sweeps (wait 2 sweeps) |
| 4.6 | Resolve the same drift in two tabs | Second tab's confirm shows an error in the dialog with a trace id (409), no crash |
| 4.7 | Screen reader / accessibility tree (DevTools → Accessibility) | Row buttons announced with context, e.g. "Accept: reporting-service → payments-service" |
| 4.8 | Keyboard only | Tab reaches rows and buttons; Enter opens dialog; Esc closes; focus returns |

## 5. Inspector — Drift tab

| # | Step | Expected |
|---|---|---|
| 5.1 | Select `notification-service`, open **Drift** tab | Only drifts touching notification-service (`legacy-batch → notification-service`), not tenant-wide |
| 5.2 | Select `api-gateway` → Drift tab | "No drift on api-gateway. Its declared and observed dependencies agree." |
| 5.3 | Resolve from the tab | Same dialogs/behaviour as §4 |
| 5.4 | Mobile | Bottom sheet; Drift tab scrolls inside the sheet; expand/collapse works |

## 6. Unresolved services

| # | Step | Expected |
|---|---|---|
| 6.1 | Side panel, no node selected | `stripe-gateway` listed: "N calls · last seen Xm ago" (or "just now") |
| 6.2 | **Create service** | Register drawer opens with name pre-filled `stripe-gateway`; Cancel leaves the row |
| 6.3 | Create service → submit | Toast "Service registered"; row disappears; service appears in catalog |
| 6.4 | **Dismiss** a row (use a throwaway name if you don't want to lose `stripe-gateway`) | Red confirm, copy says it won't come back; row gone; count decrements |
| 6.5 | > 20 rows (only if present) | "Show more (x of y)"; at 200 a "Showing the first 200 of N" note |

## 7. Catalog search (`/catalog`)

| # | Step | Expected |
|---|---|---|
| 7.1 | Type "pay" in search | Previous results stay visible, dimmed, while the query runs; then `payments-service` |
| 7.2 | Search for nonsense | "No services match your filters" |
| 7.3 | Search after Create service (6.3) | `stripe-gateway` found |

## 8. Ownership / orphans (3.4)

| # | Step | Expected |
|---|---|---|
| 8.1 | `/risks` | 3 orphan rows: `legacy-batch`, `webhook-relay`, `rewards-calculator` |
| 8.2 | In catalog, clear the team of `billing-service`, then `/risks` | `billing-service` appears as orphan within seconds; reassign Payments → it disappears |

## Record

| Date | Tester | Build (git sha) | Result | Notes / trace ids |
|---|---|---|---|---|
| | | | | |
