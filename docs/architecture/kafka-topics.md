# Cartogra — Kafka Topics

## Naming Convention

`cartogra.{domain}.{entity}.{event}`

All topics are created explicitly. No speculative topics — a topic is created only when its first real producer/consumer exists.

This document was last reconciled against code on 2026-08-20 (Phase 0, story 0.4). Ground truth was the
producer/consumer code itself (`grep` for `@KafkaListener` and topic constants across `services/*/src/main`),
not the domain descriptions below, which can drift again — re-run that grep before trusting this file blindly.

---

## Implementation Status

| Domain | Real topics today | Notes |
| ------ | ------------------ | ----- |
| Registry | 6 (service ×3, team ×3) | All produced; none consumed yet — Topology/Intelligence, the planned consumers, don't exist |
| Ingestion | 4 (`service.discovered`, `ownership.resolved`, `sync.completed`, `sync.command`) | `service.discovered` and `ownership.resolved` are consumed by Registry; `sync.completed` has no consumer; `sync.command` is produced *and* consumed inside Ingestion itself despite its `registry.` prefix — see below |
| Topology | 0 | Service has schema + CI + IT harness only (Phase 0); no service class, controller, producer, or consumer exists |
| Contract, Intelligence | 0 | Empty directories — no code at all |

There is no "Notification" domain and no "Notification worker" module. An earlier version of this document
carried a Notification domain with a `cartogra.notification.sent` topic; `docs/roadmap.md` §12 records it as
removed — "No producer, no consumer, no module. Violates the project's own no-speculative-topics rule."

---

## Topic Catalog — real (a producer exists in code)

### Registry domain

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.registry.service.registered` | Registry (`ServiceLifecycleEventProducer`) | none | New service created |
| `cartogra.registry.service.updated` | Registry (`ServiceLifecycleEventProducer`) | none | Service metadata changed |
| `cartogra.registry.service.deleted` | Registry (`ServiceLifecycleEventProducer`) | none | Service soft-deleted |
| `cartogra.registry.team.created` | Registry (`TeamLifecycleEventProducer`) | none | New team added |
| `cartogra.registry.team.updated` | Registry (`TeamLifecycleEventProducer`) | none | Team renamed |
| `cartogra.registry.team.deleted` | Registry (`TeamLifecycleEventProducer`) | none | Team deleted |

### Ingestion domain

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.ingestion.service.discovered` | Ingestion (`ServiceDiscoveredProducer`) | Registry (`RegistryServiceDiscoveryConsumer`) | Repo or K8s Service warrants a catalog entry |
| `cartogra.ingestion.ownership.resolved` | Ingestion (`OwnershipResolvedProducer`) | Registry (`OwnershipResolvedConsumer`) | CODEOWNERS/K8s-label ownership resolved for a repo |
| `cartogra.ingestion.sync.completed` | Ingestion (`SyncResultProducer`) | none (tests subscribe directly; no production consumer) | A Sync Job finished (success or failure) |
| `cartogra.registry.sync.command` | Ingestion (`SyncCommandProducer`) | Ingestion (`SyncCommandConsumer`) | **Not** a Registry→Ingestion handoff despite the `registry.` prefix. `ScmConnectionService`/`WebhookService`/`ScheduledSyncService` in Ingestion publish this to queue a sync job for its own `SyncCommandConsumer`; the name is a leftover from before SCM Connections moved from Registry to Ingestion (see `services/registry/CONTEXT.md`) |

### Dead-letter topics

Only the two Registry consumers above have DLQ handling configured (`registry/config/KafkaConfig.java`, a
`DefaultErrorHandler` with a 3-attempt fixed backoff before recovery). The DLQ topic is the source topic with
a `.dlq` suffix — **not** the `cartogra.dlq.{suffix}` prefix pattern this document used to describe:

- `cartogra.ingestion.service.discovered.dlq`
- `cartogra.ingestion.ownership.resolved.dlq`

Ingestion's own consumer (`SyncCommandConsumer`) has no error handler configured at all — no retry, no DLQ.
A poison `cartogra.registry.sync.command` message currently blocks that consumer's partition rather than
being recovered.

DLQ records carry Spring Kafka's standard `DeadLetterPublishingRecoverer` headers
(`kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`, `kafka_dlt-original-topic`,
`kafka_dlt-original-partition`, `kafka_dlt-original-offset`, `kafka_dlt-original-timestamp`) plus the
propagated `traceparent` — not the custom `dlq-reason`/`dlq-message`/`dlq-attempt`/`dlq-original-topic`
headers this document used to list; those were never implemented.

---

## Proposed — no producer or consumer exists; do not build against these names yet

Every topic in this section is unimplemented. Each is named in `docs/roadmap.md`; treat the roadmap, not this
table, as authoritative on exact timing, and re-check the wording there before relying on a name below.

| Topic | Direction | Roadmap story | Status |
| ----- | --------- | -------------- | ------ |
| `cartogra.observability.spans` | OTel Collector → Topology (`OtelSpanWorker`) | 3.1 | Replaces an earlier plan where Ingestion forwarded observed dependencies; Ingestion is not on this path |
| `cartogra.registry.service.ownership-changed` | Registry → Topology, orphan-risk flagging only | ADR-0027 | **Conflicts with roadmap 3.3**, which instead describes Topology consuming the already-real `cartogra.ingestion.ownership.resolved`. See the reconciliation note in `CONTEXT-MAP.md`. Do not implement either side until that's resolved |
| `cartogra.platform.audit.recorded` | Topology → Registry | 4.1 | Registry writes audit events directly; Topology publishes this for Registry's consumer |
| `cartogra.ingestion.spec.discovered` | Ingestion → Contract | 5.8 | Named `spec.discovered`, not `spec.updated` as an earlier version of this document had it |
| `cartogra.platform.dead-letter` | platform-wide DLQ replay | 7.5 | Distinct from the per-service `.dlq` topics above |

Topology's own future producer topics (3.4, deferred to Phase 6 per the roadmap's "no topic without a
consumer" rule — Intelligence is the consumer and doesn't exist yet): `dependency.declared`,
`dependency.observed`, `dependency.removed`, `drift.detected`. Exact `cartogra.topology.*` names are not yet
committed in the roadmap text, so none are asserted here.

No Kafka topic names are committed anywhere in `docs/roadmap.md` for Contract (beyond `spec.discovered`
above) or for Intelligence. An earlier version of this document speculated `check.passed`/`check.failed`/
`version.published` for Contract and `digest.generated`/`alert.raised` for Intelligence — those are dropped
here as unimplemented and unplanned; do not treat them as pending work.

---

## Message Envelope

Every Kafka message payload MUST match this structure:

```json
{
  "event_id": "<UUIDv4>",
  "event_type": "service.registered",
  "entity_id": "<UUID>",
  "tenant_id": "<UUID>",
  "timestamp": "2026-04-30T12:00:00Z",
  "version": 1,
  "correlation_id": "<UUID>",
  "payload": {}
}
```

### Rules

- **Message key:** primary entity UUID — ensures partition ordering per entity.
- **`traceparent` header:** W3C format, injected by producer, extracted by consumer to restore trace context.
- **`event_id`:** UUIDv4 — used for consumer-side idempotency checks.
- **`version`:** integer; increment when the `payload` schema evolves in a breaking way.

---

## Retention & Partitioning

No per-topic retention or partition override exists in this repo's Kafka config today — this table describes
target defaults, not something applied and verifiable in code yet.

| Category | Default retention | Partitions |
| ---------| -----------------| -----------|
| Domain events | 7 days | 6 |
| Ingestion events | 3 days | 12 |
| DLQ topics | 30 days | 3 |

Partitions are a starting point; review against throughput at 1k+ tenants.

---

## References

- [system-overview.md](system-overview.md)
- [patterns.md — Kafka Producer + Consumer](../../.claude/rules/patterns.md)
- `docs/roadmap.md` — plan of record for every topic in the "Proposed" section above
