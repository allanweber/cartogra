# 5.10 — One topic per entity
Issue: #139 (Phase 5 tracker; 5.10 has no own issue) · Branch: `feat/phase5-contracts`
Plan file in repo: `plans/024-one-topic-per-entity.md`

## Context
Registry publishes service lifecycle on 3 topics, team lifecycle on 3. Kafka orders only within a topic → topology can read `service.deleted` before `service.updated` for the same id (the `GraphNodeEventConsumer` Javadoc claims ordering is guaranteed; it is not). Rule now in `backend.md`/`patterns.md`/`kafka-topics.md`: lifecycle events → `cartogra.{domain}.{entity}.changed`, keyed by entity id, event in `event_type`. Runs first in P5 so contract (5.1) consumes `service.changed` from day one.

Found: topics auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` in compose + helm); no `NewTopic` beans for registry topics. Demo Kafka persistent (PVC). DLQ = `<source>.dlq` via `DeadLetterPublishingRecoverer` in `services/topology/config/KafkaConfig.java` → moves automatically to `cartogra.registry.service.changed.dlq`. Envelope `event_type` already set per event; `GraphNodeService.applyLifecycleEvent` already dispatches on `eventType` → no domain change.

## Decisions
- **Plain cutover**, single deploy, no backfill, no dual-listen (user choice). Events in the rollout window may be lost to `graph_nodes`; accepted.
- **Old topics deleted by one-off manual command** after deploy verified (Verification §). No code/CI for it.
- **Auto-create stays** for the new topics — same as today; no `NewTopic` beans (no retention override needed).
- **Topology consumer group unchanged** (`topology-consumer`, `auto-offset-reset: earliest`): new topic has no committed offset → reads from start, which is empty at cutover.
- **Team producer stays fire-and-forget** (not sync like service) — unchanged behaviour, no consumer exists; out of scope.
- **Docs**: update live docs; leave `docs/bip/*` (historical record).

## Implementation steps
1. `services/registry/.../infrastructure/kafka/ServiceLifecycleEventProducer.java` → one `TOPIC = "cartogra.registry.service.changed"`; `publish(eventType, service)` drops the topic param.
2. `services/registry/.../infrastructure/kafka/TeamLifecycleEventProducer.java` → one `TOPIC = "cartogra.registry.team.changed"`, same shape.
3. `services/topology/.../infrastructure/kafka/GraphNodeEventConsumer.java` → `topics = "cartogra.registry.service.changed"`; fix Javadoc: one topic, keyed by service id → per-service order guaranteed.
4. Tests (rename topics, assert `event_type`):
   - `registry/.../infrastructure/kafka/ServiceLifecycleEventProducerTest.java`, `TeamLifecycleEventProducerTest.java` → topic `*.changed`, assert envelope `event_type` per method.
   - `registry/.../ServiceCrudIT.java`, `TeamCrudIT.java` → subscribe one topic; `pollForRecord` by key + `event_type` (registered/updated/deleted all share key now — poll must filter on `event_type`, or read the 3 records in order).
   - `registry/.../RegistryServiceDiscoveryConsumerIT.java` → `service.changed`, assert `event_type = service.registered`.
   - `topology/.../infrastructure/kafka/GraphNodeEventConsumerIT.java` → all sends to `service.changed`. Add case: `registered`→`updated`→`deleted` sent back-to-back on the one topic, same key → node ends soft-deleted (proves delivery in send order end-to-end). Plus DLQ test: poison record on `service.changed` lands on `cartogra.registry.service.changed.dlq`.
5. Docs:
   - `docs/architecture/kafka-topics.md` → Registry catalog = 2 rows (`service.changed`, `team.changed`, list `event_type`s); DLQ list → `cartogra.registry.service.changed.dlq`; Implementation Status "6" → "2"; drop "catalog reflects code before 5.10" sentence; reconcile date.
   - `services/registry/CONTEXT.md` (produced table), `services/topology/CONTEXT.md` (consumed table), `services/intelligence/CONTEXT.md` (planned consumed), `CONTEXT-MAP.md:86` (`service.changed`, `event_type service.deleted`), `docs/architecture/system-overview.md:144`.
   - `docs/roadmap.md` → tick 5.10.
6. Grep gate: `grep -rn "registry\.service\.\(registered\|updated\|deleted\)\|registry\.team\.\(created\|updated\|deleted\)" --exclude-dir={node_modules,bip,plans}` → only roadmap 5.10 prose.

## Acceptance criteria
- [ ] Registry publishes every service lifecycle event on `cartogra.registry.service.changed`, key = service id, `event_type` ∈ `service.{registered,updated,deleted}`.
- [ ] Registry publishes every team lifecycle event on `cartogra.registry.team.changed`, key = team id, `event_type` ∈ `team.{created,updated,deleted}`.
- [ ] Topology `GraphNodeEventConsumer` listens only to `service.changed`; failures go to `cartogra.registry.service.changed.dlq`.
- [ ] No code/test/live doc references the 6 old topic names (grep gate, step 6).
- [ ] Acme `verify` passes unchanged: graph nodes appear after `load`.
- [ ] Old 6 topics + 3 `.dlq` deleted on demo cluster after deploy.

## Tests
- Unit: producer tests (topic, key, `event_type`).
- IT (Testcontainers Kafka): registry CRUD ITs, discovery IT, topology consumer IT incl. ordered register→update→delete and DLQ.
- E2E: none — no UI change. Acme `verify` is the end-to-end check.

## Verification
- `./gradlew :services:registry:build :services:topology:build` then `./gradlew build`.
- Local: `docker compose -f infra/docker-compose/docker-compose.yml up`, create/update/delete a service via UI → `kafka-topics.sh --list` shows `service.changed`; topology graph reflects it.
- After demo deploy + Acme verify green, one-off:
  ```
  kubectl -n cartogra exec <kafka-pod> -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic 'cartogra\.registry\.(service\.(registered|updated|deleted)|team\.(created|updated|deleted))(\.dlq)?'
  ```
  (regex supported by `--topic`; `--list` first to confirm match set). Local compose: same via `docker exec cartogra-kafka`, or `down -v`.

## Out of scope
- Backfill/dual-listen for the rollout window.
- Making team producer synchronous; team consumer (none exists — rule-4 exception accepted earlier).
- Other single-event topics (`ingestion.*`, `audit.recorded`, `registry.sync.command` rename).
- Explicit `NewTopic`/retention for registry topics.
