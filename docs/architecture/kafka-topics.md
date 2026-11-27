# Cartogra — Kafka Topics

## Naming Convention

`cartogra.{domain}.{entity}.{event}`

**Lifecycle events: one topic per entity** — `cartogra.{domain}.{entity}.changed`, keyed by entity id, the event in the envelope's `event_type`. Kafka orders only within a topic, so per-event topics let a `deleted` be read before an `updated`. Registry's service and team lifecycle moved to `registry.service.changed` / `registry.team.changed` in roadmap 5.10.

All topics are created explicitly. No speculative topics — a topic is created only when its first real producer/consumer exists.

This document was last reconciled against code on 2026-11-11 (roadmap 5.10). Ground truth was the
producer/consumer code itself (`grep` for `@KafkaListener` and topic constants across `services/*/src/main`),
not the domain descriptions below, which can drift again — re-run that grep before trusting this file blindly.

---

## Implementation Status

| Domain | Real topics today | Notes |
| ------ | ------------------ | ----- |
| Registry | 2 (`service.changed`, `team.changed`) | `service.changed` consumed by Topology; `team.changed` has no consumer |
| Ingestion | 5 (`service.changed`, `ownership.resolved`, `spec.discovered`, `sync.completed`, `sync.command`) | `service.changed` and `ownership.resolved` are consumed by Registry; `spec.discovered` is consumed by Contract; `sync.completed` has no consumer; `sync.command` is produced *and* consumed inside Ingestion itself despite its `registry.` prefix — see below |
| Topology | 1 (`dependency.changed`) | Sent through a transactional outbox (`shared/web` `OutboxWriter` + `OutboxRelay`); consumed by Contract. Topology also consumes `registry.service.changed` and `observability.spans` |
| Contract | 0 | Consumes `registry.service.changed` (`service.registered`/`service.updated`/`service.deleted`), `ingestion.spec.discovered` and `topology.dependency.changed`; produces only `platform.audit.recorded` |
| Intelligence | 0 | Empty directory — no code at all |

There is no "Notification" domain and no "Notification worker" module. An earlier version of this document
carried a Notification domain with a `cartogra.notification.sent` topic; `docs/roadmap.md` §12 records it as
removed — "No producer, no consumer, no module. Violates the project's own no-speculative-topics rule."

---

## Topic Catalog — real (a producer exists in code)

### Registry domain

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.registry.service.changed` | Registry (`ServiceLifecycleEventProducer`) | Topology (`GraphNodeEventConsumer`), Contract (`ServiceEventConsumer`, group `contract-consumer`: keeps the name and `externalId` projection, applies PENDING discovered specs, cascades `service.deleted`) | Service lifecycle, keyed by service id; `event_type` `service.registered` / `service.updated` / `service.deleted` (soft delete) |
| `cartogra.registry.team.changed` | Registry (`TeamLifecycleEventProducer`) | none | Team lifecycle, keyed by team id; `event_type` `team.created` / `team.updated` (rename) / `team.deleted` |

### Topology domain

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.topology.dependency.changed` | Topology (`DependencyEventPublisher` → `outbox` → `OutboxRelay`) | Contract (`DependencyEventConsumer`, group `contract-consumer`) | Edge lifecycle, keyed by edge id (one row of `dependencies`); `event_type` `dependency.declared` / `dependency.observed` / `dependency.removed`; payload `{edgeId, sourceServiceId, targetServiceId, dependencyType, protocol}`. A PUT that changes source, target or protocol sends `dependency.declared` again; an observed refresh sends nothing. Written in the transaction of the edge change and sent in `id` order by one relay at a time; at-least-once. Migration V017 backfilled the edges that existed before the topic |

### Ingestion domain

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.ingestion.service.changed` | Ingestion (`ServiceChangedProducer`) | Registry (`RegistryServiceDiscoveryConsumer`) | Discovered services. `event_type` `service.discovered`: a repo or K8s Service is present (key = external id, or `cluster/namespace/name`). `service.removed`: a K8s Service was deleted (key = `cluster/namespace/name`). `service.resynced`: the full set of tenant Services in one cluster at `observedAt` (key = cluster name); Registry removes the cluster from rows not in the set. An empty set is sent when a cluster is deleted or renamed |
| `cartogra.ingestion.ownership.resolved` | Ingestion (`OwnershipResolvedProducer`) | Registry (`OwnershipResolvedConsumer`) | CODEOWNERS/K8s-label ownership resolved for a repo |
| `cartogra.ingestion.spec.discovered` | Ingestion (`SpecDiscoveredProducer`) | Contract (`SpecDiscoveredConsumer`) | An OpenAPI file that a sync found at a probe path (ADR-0018, roadmap 5.8). One event for each repo with a spec; the first hit in probe order wins. `event_type` `spec.discovered`; key = `entityId` = UUIDv5 of `(tenant, repository, path)`. Payload carries the file in base64 and its SHA-256, no service id: Contract resolves the repository to a service. Specs over 512 KiB or empty are not sent |
| `cartogra.ingestion.sync.completed` | Ingestion (`SyncResultProducer`) | none (tests subscribe directly; no production consumer) | A Sync Job finished (success or failure) |
| `cartogra.registry.sync.command` | Ingestion (`SyncCommandProducer`) | Ingestion (`SyncCommandConsumer`) | **Not** a Registry→Ingestion handoff despite the `registry.` prefix. `ScmConnectionService`/`WebhookService`/`ScheduledSyncService` in Ingestion publish this to queue a sync job for its own `SyncCommandConsumer`; the name is a leftover from before SCM Connections moved from Registry to Ingestion (see `services/registry/CONTEXT.md`) |

### Platform

| Topic | Producer | Consumers today | Description |
| ----- | -------- | ---------------- | ----------- |
| `cartogra.platform.audit.recorded` | Topology, Ingestion, Contract (`KafkaAuditEventAdapter`, `shared:web`) | Registry (`AuditRecordedConsumer`) | A state change for the audit trail (ADR-0017). Envelope payload is the `AuditEvent`; key = entity id. Registry inserts idempotently on `AuditEvent.eventId`. Registry and Gateway don't publish — they write `audit_events` directly |

### Dead-letter topics

The two Registry consumers, Topology's `GraphNodeEventConsumer` and Contract's `ServiceDeletedConsumer` have DLQ
handling configured (`{registry,topology,contract}/config/KafkaConfig.java`, a `DefaultErrorHandler` with a 3-attempt fixed backoff before
recovery). The DLQ topic is the source topic with a `.dlq` suffix — **not** the `cartogra.dlq.{suffix}` prefix
pattern this document used to describe:

- `cartogra.ingestion.service.changed.dlq`
- `cartogra.ingestion.ownership.resolved.dlq`
- `cartogra.registry.service.changed.dlq` — shared by Topology's and Contract's consumers; each lands its own poison records there
- `cartogra.ingestion.spec.discovered.dlq` — Contract's `SpecDiscoveredConsumer`; only records that cannot be read. An invalid spec is stored as `REJECTED` and acked

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
| `cartogra.observability.spans` | OTel Collector → Topology (`OtelSpanWorker`) | 3.1 | **Implemented in 3.1** (consumer side; the collector is the producer). Payload is raw OTLP/JSON from the collector's `kafka` exporter — a documented exception to the envelope rule (ADR-0029). Tenant comes from resource attribute `cartogra.tenant.id`; unattributable records go to `cartogra.observability.spans.dlq`. Ingestion is not on this path |
| `cartogra.registry.service.ownership-changed` | Registry → Topology, orphan-risk flagging only | ADR-0027 | **Not built — superseded by ADR-0028.** Topology reads `graph_nodes.team_id`, already synced by `service.{registered,updated}`; roadmap 3.4 was rescoped to the CODEOWNERS team mapping and adds no topic |
| `cartogra.platform.audit.recorded` | Topology → Registry | 4.1 | Registry writes audit events directly; Topology publishes this for Registry's consumer |
| `cartogra.platform.dead-letter` | platform-wide DLQ replay | 8.5 | Distinct from the per-service `.dlq` topics above |

Topology's `dependency.declared`, `dependency.observed`, `dependency.removed` shipped in 5.4 on one topic,
`cartogra.topology.dependency.changed` (see the Topology domain table above). `drift.detected` stays in 7.7
until Intelligence consumes it.

No Kafka topic names are committed anywhere in `docs/roadmap.md` for Contract (beyond `spec.discovered`,
now live) or for Intelligence. An earlier version of this document speculated `check.passed`/`check.failed`/
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
- [backend.md — Kafka](../../.claude/rules/backend.md)
- `docs/roadmap.md` — plan of record for every topic in the "Proposed" section above
