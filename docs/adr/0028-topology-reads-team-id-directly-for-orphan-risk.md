# Topology reads `graph_nodes.team_id` directly for orphan risk, superseding ADR-0027

ADR-0027 decided that orphan risk on `GET /v1/risks` would require a new Kafka topic (`cartogra.registry.service.ownership-changed`) and a new Topology consumer, reasoning that Topology had no other way to learn about ownership changes. That premise no longer holds: by the time 2.4/2.5 were implemented, `graph_nodes.team_id` was already live-synced through the existing `cartogra.registry.service.{registered,updated}` → `GraphNodeEventConsumer` pipeline — Registry's `assignOwner` (whether called directly or driven by the `ownership.resolved` CODEOWNERS flow) already builds an updated `Service` with the new `teamId` and publishes `service.updated`, and `JdbcGraphNodeRepository.upsert` already writes `team_id` (including back to `NULL`) on every such event. This is the same lifecycle sync that already populates `tier` and `healthStatus` for blast radius and SPOF scoring — nothing new was needed to get ownership state into Topology's projection.

Given that, `SpofService` and `RiskService` read `graph_nodes.team_id IS NULL` directly instead. The dedicated `ownership-changed` topic and consumer described in ADR-0027 are not built.

## Consequences

- No new Kafka topic, no new consumer, no new coupling beyond the lifecycle-event Conformist relationship Topology already had with Service Catalog. `CONTEXT-MAP.md`'s "Orphan-risk ownership source is unresolved between two docs" reconciliation note is resolved: neither ADR-0027's topic nor `docs/roadmap.md` 3.3's `cartogra.ingestion.ownership.resolved` is what actually feeds orphan risk — it's the general `service.{registered,updated}` sync, already live since Phase 1.
- Issue `#122` ("[3.3] Orphan risk from ownership events") describes building the ADR-0027 mechanism; its premise is moot now that orphan risk already works without it. Left open, not touched by this change — worth revisiting when that issue is next picked up.
- One residual gap, unchanged from before: `team_id` only reflects *explicit* team assignment (manual or CODEOWNERS-resolved via Registry's existing `ownership.resolved` consumer). It does not reflect any *automatic* ownership inference Topology itself might someday need independent of Registry's own resolution — if that ever becomes a requirement, it's a new decision, not a revival of this one.

## References

- ADR-0027 (superseded by this ADR)
- `services/registry/src/main/java/io/cartogra/registry/domain/ServiceService.java` (`assignOwner`)
- `services/topology/src/main/java/io/cartogra/topology/infrastructure/kafka/GraphNodeEventConsumer.java`
- `services/topology/src/main/java/io/cartogra/topology/infrastructure/jdbc/JdbcGraphNodeRepository.java` (`upsert`)
