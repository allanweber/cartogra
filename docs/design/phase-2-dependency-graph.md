# Phase 2 Design Brief — Dependency Graph

Confirmed via `/shape dependency graph` grilling session, per checklist item 2.1. Covers the D3 graph view, blast radius panel, SPOF findings, cycle warnings, drift overlay, and Risks page. This is the brief that unblocks checklist items 2.3–2.6 (Phase 2 UI code).

Ubiquitous language used below (Dependency, Blast radius, Cycle, Drift, SPOF, Orphan, Risk) is defined in [`services/topology/CONTEXT.md`](../../services/topology/CONTEXT.md); UI-specific terms (Inspector panel, Edge mode, Standing badge) in [`frontend/CONTEXT.md`](../../frontend/CONTEXT.md).

## Page structure

One D3 canvas is the source of truth for the Graph page (`/graph`). A right-side **Inspector panel** reacts to node selection and canvas-level controls. The Risks page (`/risks`) stays a separate, tenant-wide aggregated list — not a tab or modal on the graph — and deep-links back into the graph.

Rejected: separate views/modals per concern (blast radius modal, SPOF list, etc.) — loses the "see the whole graph shape at once" value and forces re-deriving node context in every view.

## Inspector panel

Three tabs:

- **Details** — name, team, tier, health. Default tab when nothing else applies.
- **Blast Radius** — becomes the active tab immediately on node click (selection *is* the trigger, no separate "show blast radius" action). Shows both directions at once:
  - Downstream (impacted-if-this-service-fails) and upstream (what-this-service-depends-on) each get a distinct highlight color; the selected node gets a third treatment (ring/glow); uninvolved nodes dim to low opacity.
  - Traversal depth is fixed server-side (path-explosion guard) — **no user-facing depth control**. If the result was truncated, show a small notice ("truncated at N hops — M services beyond this depth not shown").
- **Drift** — tenant-wide, does not require node selection. Lists all active drift records (service names resolved client-side, `drift_type`, `detected_at`, a Resolve button calling `POST /v1/drifts/{id}/resolve`). Selecting a row highlights that edge on the canvas.

Rejected: a depth slider (adds client-supplied depth validation server-side for no clear user need); per-edge-popover-only drift resolution (no way to triage all outstanding drift at once).

## Standing visual encodings (always-on, never toggle-gated)

- **SPOF badge** — persistent marker on any node currently flagged as a SPOF. Treated as a standing property of the node (like health), not a transient overlay.
- **Cycle highlight** — edges participating in a cycle get a persistent dashed-red stroke, for the same reason and to keep SPOF/cycle treatment symmetric (one visual rule to learn, not two).

Rejected: toggle-gating either of these — makes them invisible unless the user remembers to check, for facts that are standing structural risk, not exploratory overlays.

## Edge mode (unified control)

One segmented control replaces what the checklist described as two separate things (2.3's "declared-vs-observed toggle" and 2.6's "drift overlay"): **All / Declared only / Observed only / Drift only**. Drift mode shows only mismatched edges, color-coded by `drift_type` (`undeclared` = observed edge with no declared counterpart; `missing` = declared edge with no observed counterpart).

Rejected: two independent toggles (declared/observed/both, plus a separate drift on/off) — more combinations to test for no clear benefit over one mental model of "what edges am I looking at."

## Legend

A collapsible legend chip (bottom-left of the canvas, closed by default) explains health color, SPOF badge, cycle styling, and the current Edge mode's color coding. Given how much visual vocabulary stacks on one canvas (health, tier, SPOF, cycle, blast radius, edge mode), relying on hover/tooltip discovery alone was rejected as insufficient for first-time users.

## Rendering approach

- **SVG**, not Canvas. Comfortably covers the ≥20-service gate criterion with large margin; keeps per-node/edge DOM elements so click/hover/selection/CSS transitions and shadcn tooltip integration stay simple. Revisit only if a tenant's real service count approaches SVG's practical ceiling (~500–1000 elements) — not expected in Phase 2's tenant base.
- **Run d3-force to convergence, then freeze.** Simulation runs once on load/data-change until `alphaMin`, positions are cached, ticking stops. Pan/zoom/select never re-triggers the simulation; only a data refresh (new nodes/edges) restarts it. This is what keeps the canvas smooth at the gate criterion's node count and beyond — a continuously-ticking simulation was rejected as the likely source of exactly the jank the gate criterion warns about.

## Risks page

- Adds a **risk-type filter** (SPOF / Cycle / Drift / Orphan) alongside the existing severity filter chips, since risks now aggregate four distinct sources into one list.
- `Risk.affectedServices: string[]` holds service **IDs**, not names (Topology never hydrates `Service` objects from Registry). Every page that needs display names — Risks, Graph — independently declares its own `useQuery({ queryKey: ['services'], ... })` and joins IDs to names/health/tier client-side. No page assumes another page fetched the catalog first; each one owns its dependency and shows its own `Skeleton` while it resolves.
- Backend keeps `explanation` and `fix` as **two separate fields** (not collapsed into one `description`) — preserves the current mock UI's "what's wrong, then what to do about it" card structure.
- Clicking a service chip on a risk card **deep-links to `/graph?serviceId=X`**, auto-selecting that node (opening its Inspector panel) and panning/zooming it into view. Turns the Risks page into an entry point into the graph rather than a dead end.
- **Orphan risk source**: Registry publishes `cartogra.registry.service.ownership-changed`; Topology consumes it solely to flag orphan status in `/v1/risks`. See [ADR-0027](../adr/0027-topology-consumes-ownership-events-for-orphan-risk.md) for why this was chosen over a client-side merge, and what it costs (new topic, new consumer, a documented exception to Topology's "IDs only" boundary).

## Type-contract fixes needed before real API wiring

- `GraphEdge.protocol` must store the backend's raw enum (`'http' | 'grpc' | 'kafka' | 'db'`), mapped to display labels (`REST`/`gRPC`/`Kafka`/`DB`) only at render time via a `PROTOCOL_LABELS` lookup — not translated at the API client boundary. Today's mock (`'REST' | 'gRPC' | 'Kafka'`, no `db`) does not match `dependencies.protocol` in `data-model.md` and silently can't represent DB dependencies.

## Explicitly deferred

Loading/error states follow the existing `frontend.md` conventions (shadcn `Skeleton` while loading, `Alert` with `traceId` on error) — no new pattern was needed for this feature.
