# ADR-0014 — Sync Command Idempotency: Concurrent-Execution Guard

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Allan Weber

---

## Context

The ingestion service consumes `cartogra.registry.sync.command` messages and executes SCM sync jobs. Each message carries a `connectionId` identifying the SCM connection to sync. Because the Kafka topic may deliver duplicate messages (at-least-once delivery) or because an operator may trigger two syncs in rapid succession, the consumer can receive two commands for the same `connectionId` before the first sync job has finished.

Without a guard, both commands would create separate `SyncJob` rows and execute concurrently against the same SCM connection. This causes:
- Redundant API calls to the SCM provider (rate-limit burn)
- Two `sync.completed` result events, confusing downstream consumers
- Potential race conditions when both jobs try to write overlapping repository data

The guard must be:
1. **Cheap**: a single SELECT before any INSERT — no distributed lock required
2. **Safe under concurrent consumers**: two consumer threads can race, but the second will see the RUNNING row created by the first (Postgres visibility is immediate after the first INSERT commits; Kafka consumers in the same group are single-threaded per partition, so partition-keyed messages naturally serialise)
3. **Transparent to the Kafka offset commit**: dropping a duplicate must not cause a consumer error (which would trigger a retry loop)

---

## Decision

Add a **concurrent-execution guard** at the top of `ExecuteSyncUseCaseImpl.execute()`:

1. Call `SyncJobRepository.findRunningForConnection(tenantId, connectionId)`.
2. If a RUNNING job exists: log `WARN` with the `connectionId` and return the existing job — no new `SyncJob` row is created.
3. If no RUNNING job exists: proceed with `save(PENDING)` → `markRunning` → sync → `markCompleted/Failed`.

The SQL for the guard filters on `status = 'RUNNING' AND deleted_at IS NULL` to avoid matching stale soft-deleted rows from previous syncs.

This approach is chosen over alternatives because:

- **Kafka exactly-once semantics (EOS)**: not available without a transactional producer/consumer pair; adds significant config complexity and is not worth it for a background sync job.
- **Database unique constraint on `(connection_id, status='RUNNING')`**: partial unique indexes on mutable status columns are fragile and require careful migration.
- **Distributed lock (Redis)**: adds a Redis dependency to the ingestion service and introduces lock-expiry edge cases.
- **Idempotent `eventId` deduplication table**: the `eventId` in the `EventEnvelope` is a UUIDv5 derived from `eventType + entityId + timestamp`, so two commands sent seconds apart have different `eventId`s — a deduplication table would not catch them.

---

## Consequences

**Positive**:
- Simple, single-SELECT guard with no new infrastructure dependency.
- The `WARN` log provides a clear audit trail when a duplicate is dropped.
- No consumer error → no retry storm.

**Negative**:
- Not strictly once-per-`eventId` idempotent: two commands with different `eventId`s but the same `connectionId` are deduplicated by connection, not by event. This is the correct semantic for sync jobs (we don't want two concurrent syncs of the same connection regardless of how many events triggered them).
- A race between two consumer threads (different partitions, same `connectionId`) could theoretically create two RUNNING jobs if both pass the guard before either commits. This is acceptable in Phase 1: Kafka partition assignment by `connectionId` key makes this unlikely, and the consequence is a redundant sync rather than data corruption.

**Neutral**:
- The `SyncJobRepository` port gains two new methods: `existsRunningForConnection` and `findRunningForConnection`. Both are pure query methods with no side effects.

---

## Stale RUNNING jobs and the reaper

**Added**: 2026-05-20 (phase 1–5 dead-end audit)

The guard above silently drops duplicate `sync.command` messages when a RUNNING job exists for the same `connectionId`. This is the correct behaviour in steady state, but it interacts badly with a specific failure mode:

> A JVM crash, OOM kill, or `kubectl delete pod` between `syncJobRepository.markRunning(job.id())` ([ExecuteSyncUseCaseImpl.java:50](../../services/ingestion/src/main/java/io/cartogra/ingestion/application/usecase/ExecuteSyncUseCaseImpl.java#L50)) and the corresponding `markCompleted` / `markFailed` call leaves the `sync_jobs` row stuck in `RUNNING` permanently. The guard will then drop **every future** sync command for that `connectionId`, silently disabling the connection until an operator deletes the row by hand.

### Mitigation: stale-job reaper

A `@Scheduled` reaper in ingestion (task 1.39a) runs every minute, finds `sync_jobs` rows where `status = 'RUNNING' AND updated_at < now() - ingestion.sync.stale-timeout` (default `PT30M`), flips them to `FAILED` with `error_message = 'reaper: timed out'`, and publishes a `cartogra.ingestion.sync.completed` failure envelope so the registry consumer (task 1.67) records the failure on `scm_connections.last_sync_status`.

### Why not a database-level fix

- A `CHECK` constraint cannot reference `now()` reliably.
- A `BEFORE UPDATE` trigger that auto-fails old RUNNING rows would race with normal completion.
- A unique partial index on `(connection_id) WHERE status = 'RUNNING'` would force the second consumer to error rather than gracefully drop — re-triggering the retry-storm we deliberately avoided in this ADR.

### Failure-mode coverage

| Mode | Behaviour |
|------|-----------|
| Sync completes normally | Guard sees no RUNNING; future sync commands proceed. |
| Sync fails with `ScmProviderException` | `markFailed` runs; future sync commands proceed. |
| JVM crash after `markRunning` | Row stays RUNNING; guard drops future commands. **Reaper detects within `stale-timeout` and recovers.** |
| Provider call hangs indefinitely | Row stays RUNNING; reaper detects after `stale-timeout` and recovers. |

### Configuration

| Property | Default | Notes |
|----------|---------|-------|
| `ingestion.sync.stale-timeout` | `PT30M` | Should be at least 2× the worst expected sync duration. |
| `ingestion.sync.reaper-interval` | `PT1M` | Scheduler cadence; tune down only if reaper latency matters. |

The reaper is additive to the guard and does not alter the duplicate-message dedup semantics described above.
