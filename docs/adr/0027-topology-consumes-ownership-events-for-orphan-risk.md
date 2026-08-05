# Topology consumes a Registry ownership event to compute orphan risk

`GET /v1/risks` (Topology) aggregates SPOF, cycle, and drift findings — all derived from Topology's own dependency graph — plus a fourth type, "orphan" (a service with no team owner), which is Registry data (`services.team_id`). Every other Topology consumption is scoped to service lifecycle events (`service.{registered,updated,deleted}`); this adds a new, narrower-purpose coupling. We considered computing orphan status client-side by merging the frontend's already-fetched service list with the SPOF/cycle/drift risks from Topology, which would have kept Topology's Registry-derived surface at zero, but chose instead to have Registry publish `cartogra.registry.service.ownership-changed` whenever `team_id` changes (including to `NULL`), consumed by Topology solely to flag orphan status in the unified `/v1/risks` response, so risk aggregation stays a single server-side concern instead of splitting across a backend endpoint and a frontend merge step.

## Considered Options

- **Client-side merge** (rejected): frontend derives orphan risk from its own `['services']` query and synthesizes it into the same `Risk[]` shape alongside the API's SPOF/cycle/drift results. Zero new backend coupling, but means "the risk list" isn't actually one list from one source — every consumer of `/v1/risks` (including future non-browser clients) would have to re-implement the merge.
- **Registry → Topology ownership event** (chosen): one authoritative `/v1/risks` response; Topology gains a narrow, single-purpose read of Registry ownership state.

## Consequences

- New Kafka topic `cartogra.registry.service.ownership-changed`, a new Topology consumer, and (likely) a nullable `team_id`/orphan-flag cache column on Topology's service-reference data — purely to support this one field; Topology does not otherwise need to know about teams.
- `services/topology/CONTEXT.md` and `CONTEXT-MAP.md`'s relationship table both change: Topology's relationship to Service Catalog is no longer limited to lifecycle-event Conformist — it also reads ownership state. A future engineer removing "unused" ownership plumbing from Topology should check `/v1/risks` first.
- If a future context also needs orphan-style ownership awareness, prefer generalizing this event rather than adding a second one-off consumer.
