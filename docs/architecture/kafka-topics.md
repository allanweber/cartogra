# Cartogra — Kafka Topics

## Naming Convention

`cartogra.{domain}.{entity}.{event}`

All topics are created explicitly. No speculative topics — a topic is created only when its first real producer/consumer exists.

---

## Topic Catalog

### Registry domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.registry.service.registered` | Registry | Topology, Intelligence | New service created |
| `cartogra.registry.service.updated` | Registry | Topology, Intelligence | Service metadata changed |
| `cartogra.registry.service.deleted` | Registry | Topology, Contract, Intelligence | Service soft-deleted |
| `cartogra.registry.team.created` | Registry | — | New team added |
| `cartogra.registry.scm_connection.created` | Registry | Ingestion | SCM provider connected |

### Ingestion domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.ingestion.repository.discovered` | Ingestion | Registry | Repo detected via SCM scan or K8s |
| `cartogra.ingestion.spec.updated` | Ingestion | Contract | OpenAPI/AsyncAPI spec file changed |
| `cartogra.ingestion.dependency.observed` | Ingestion | Topology | OTel-derived dependency edge |

### Topology domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.topology.graph.updated` | Topology | Intelligence | Graph snapshot after changes |
| `cartogra.topology.drift.detected` | Topology | Intelligence, Notification | Declared vs observed drift found |
| `cartogra.topology.cycle.detected` | Topology | Intelligence, Notification | Circular dependency found |

### Contract domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.contract.check.passed` | Contract | — | Compatibility check clean |
| `cartogra.contract.check.failed` | Contract | Intelligence, Notification | Breaking change detected |
| `cartogra.contract.version.published` | Contract | Intelligence | New spec version accepted |

### Intelligence domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.intelligence.digest.generated` | Intelligence | Notification | Weekly architecture digest ready |
| `cartogra.intelligence.alert.raised` | Intelligence | Notification | Anti-pattern or anomaly detected |

### Notification domain

| Topic | Producer | Consumers | Description |
|-------|----------|-----------|-------------|
| `cartogra.notification.sent` | Notification worker | — | Outbound Slack/Teams/webhook dispatched |

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

## Dead Letter Queue (DLQ)

Each consumer group publishes unprocessable messages to a corresponding DLQ topic:

`cartogra.dlq.{original-topic-suffix}`

Example: `cartogra.dlq.registry.service.registered`

DLQ messages include the original payload plus headers:

| Header | Value |
|--------|-------|
| `dlq-reason` | Exception class name |
| `dlq-message` | Exception message (truncated to 512 bytes) |
| `dlq-attempt` | Retry count at time of DLQ |
| `dlq-original-topic` | Source topic |
| `traceparent` | Preserved from original message |

---

## Retention & Partitioning

| Category | Default retention | Partitions |
|----------|------------------|------------|
| Domain events | 7 days | 6 |
| Ingestion events | 3 days | 12 |
| DLQ topics | 30 days | 3 |

Partitions are a starting point; review against throughput at 1k+ tenants.

---

## References

- [system-overview.md](system-overview.md)
- [patterns.md — Kafka Producer + Consumer](../../.claude/rules/patterns.md)
