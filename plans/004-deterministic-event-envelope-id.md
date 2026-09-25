# Plan 004: Make `EventEnvelope.eventId` deterministic instead of wall-clock-derived

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md`.
>
> **Drift check (run first)**: `git diff --stat d249c0f..HEAD -- shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java`
> If that file changed since this plan was written, compare the "Current
> state" excerpt below against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: MED
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `d249c0f`, 2026-09-24

## Why this matters

`EventEnvelope.of()` — the one place every Kafka producer in this repo constructs an event — hashes `Instant.now()` into `eventId` (`shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java:11-14`). This means two calls to `EventEnvelope.of()` with byte-for-byte identical `eventType`/`entityId`/`tenantId`/`payload` still produce two different `eventId`s, because the wall-clock timestamp baked into the hash always differs.

This matters because topology's `processed_events` table exists specifically to make event processing idempotent: its own migration comment says "a replayed envelope hits the primary-key conflict below and is treated as a no-op" (`services/topology/src/main/resources/db/migration/V005__create_processed_events.sql`), and `GraphNodeService.applyLifecycleEvent()`'s own Javadoc claims the same thing (`services/topology/src/main/java/io/cartogra/topology/domain/GraphNodeService.java:39-42`). Both are describing a guarantee that a wall-clock-derived `eventId` cannot actually provide: if the same logical event is ever reconstructed and republished (for example, an upstream Kafka message redelivery that causes a registry-side consumer to re-run a create/update flow that itself calls a lifecycle-event producer), the two resulting envelopes carry different `eventId`s and the dedupe ledger cannot recognize them as the same event.

**Important context you would not otherwise have**: `docs/adr/ADR-0014-sync-command-idempotency.md` already documents this exact limitation for one specific producer (ingestion's `sync.command`) and explicitly rejected "idempotent `eventId` deduplication" as a mechanism *because* "two commands sent seconds apart have different `eventId`s" — and built a separate concurrent-execution guard instead. That ADR's decision for `sync.command` dedup strategy is **out of scope and must not be touched** — it's a deliberate, accepted design for that one case. This plan fixes the underlying `EventEnvelope` primitive so that (a) the `processed_events` ledger's own stated guarantee actually holds for the producers that rely on it, and (b) a future producer that *does* want content-based dedup (which ADR-0014 says isn't currently possible) has that option. It does not require or imply reopening ADR-0014.

## Current state

- `shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java` — the only file in scope:

```java
public record EventEnvelope<P>(
        UUID eventId, String eventType, UUID entityId, UUID tenantId,
        Instant timestamp, int version, UUID correlationId, P payload) {

    public static <P> EventEnvelope<P> of(String eventType, UUID entityId, UUID tenantId, int version, P payload) {
        Instant now = Instant.now();
        return new EventEnvelope<>(
                UuidV5.fromNames(eventType, entityId.toString(), now.toString()),
                eventType, entityId, tenantId, now, version, UUID.randomUUID(), payload);
    }
}
```

- `shared/common/src/main/java/io/cartogra/common/event/UuidV5.java` — the hashing helper `EventEnvelope` calls. Do not modify it; its `fromNames(String...)` signature already accepts a variable number of name components, which is all this plan needs.
- Every payload type ever passed as `P` to `EventEnvelope.of(...)` is a Java **record** (verified: `Service`, `Team`, `ServiceDiscoveredPayload`, `OwnershipResolvedPayload`, `SyncResultPayload`, `SyncCommandPayload`/`ScmConnection`). Records generate a structural `toString()` covering every component field, which is what makes the fix below deterministic and content-sensitive without touching any caller.
- Callers (for context only — **you will not modify any of these files**, the fix is entirely inside `EventEnvelope.of()`):
  - `services/registry/src/main/java/io/cartogra/registry/infrastructure/kafka/ServiceLifecycleEventProducer.java:56`
  - `services/registry/src/main/java/io/cartogra/registry/infrastructure/kafka/TeamLifecycleEventProducer.java:39`
  - `services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/kafka/OwnershipResolvedProducer.java:31`
  - `services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/kafka/ServiceDiscoveredProducer.java:30`
  - `services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/kafka/SyncResultProducer.java:40`
  - `services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/kafka/SyncCommandProducer.java:30`
- No existing test in the repo asserts that two `EventEnvelope.of()` calls for the same logical event produce *different* `eventId`s (checked `GraphNodeServiceTest.java`, `GraphNodeEventConsumerIT.java` — they only read back `env.eventId()` from a single constructed envelope, never compare two). This fix will not break any existing assertion.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Build + test shared:common | `./gradlew :shared:common:test` | exit 0, all pass |
| Full affected build | `./gradlew :shared:common:build :services:registry:build :services:ingestion:build :services:topology:build` | exit 0, all pass (these four modules depend on `shared:common`) |
| Checkstyle/format (bundled in `build`) | (included above) | no violations |

## Scope

**In scope** (the only file you should modify, plus one new test file):
- `shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java`
- `shared/common/src/test/java/io/cartogra/common/event/EventEnvelopeTest.java` (create — this directory does not exist yet, see Step 2)

**Out of scope** (do NOT touch, even though they look related):
- `UuidV5.java` — the hashing primitive is correct and reused as-is.
- Any producer class listed above — none needs a code change; the fix lives entirely in `EventEnvelope.of()`'s existing `payload` parameter.
- `docs/adr/ADR-0014-sync-command-idempotency.md` and ingestion's sync-command concurrent-execution guard — that mechanism is an intentionally separate, already-accepted design and is unaffected by this change (the guard doesn't rely on `eventId` at all).
- `timestamp` field — leave it deriving from `Instant.now()`; it is observability metadata (when this envelope was constructed), not an identity input, and downstream code may reasonably expect it to reflect real construction time.

## Git workflow

- Branch: do NOT create a new named branch. Commit on whatever branch the isolated worktree starts on — it's disposable. The reviewer extracts the approved diff onto the shared `phase-1-gate-improve` branch as uncommitted changes and commits it there only after the operator explicitly approves.
- Commit message style: conventional commits, e.g. `fix(shared): derive EventEnvelope eventId from payload content, not wall clock`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Make `eventId` a deterministic hash of identity + content

Edit `shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java` so `of()` hashes the payload's structural content instead of the current timestamp:

```java
public static <P> EventEnvelope<P> of(String eventType, UUID entityId, UUID tenantId, int version, P payload) {
    Instant now = Instant.now();
    return new EventEnvelope<>(
            UuidV5.fromNames(eventType, entityId.toString(), tenantId.toString(),
                    String.valueOf(version), String.valueOf(payload)),
            eventType, entityId, tenantId, now, version, UUID.randomUUID(), payload);
}
```

Notes on this exact shape:
- `String.valueOf(payload)` (not `payload.toString()`) is deliberate — it's null-safe if `payload` is ever `null`, matching defensive-null-handling elsewhere in the codebase.
- `tenantId.toString()` and `String.valueOf(version)` are added to the hash inputs alongside the existing `eventType`/`entityId` — this keeps events for the same entity but different tenants (shouldn't happen, but costs nothing to guard) and different schema versions from ever colliding.
- `timestamp` (the `now` local variable) keeps being computed and stored exactly as before — only its use inside the `UuidV5.fromNames(...)` call is removed.

**Verify**: `./gradlew :shared:common:build` → exit 0, checkstyle/spotless pass (no import or formatting changes needed beyond what's shown).

### Step 2: Add a determinism contract test

Create `shared/common/src/test/java/io/cartogra/common/event/EventEnvelopeTest.java` (new file — no `src/test` tree exists yet for this module; check `shared/common/build.gradle.kts` already declares JUnit/AssertJ test dependencies before assuming you need to add them — it should, since every other module in the repo uses the same test stack).

```java
package io.cartogra.common.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    private record SamplePayload(String name, int count) {}

    @Test
    void sameLogicalEventProducesTheSameEventId() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);
        EventEnvelope<SamplePayload> second = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);

        assertThat(first.eventId()).isEqualTo(second.eventId());
    }

    @Test
    void differentPayloadContentProducesADifferentEventId() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        EventEnvelope<SamplePayload> first =
                EventEnvelope.of("service.updated", entityId, tenantId, 1, new SamplePayload("svc", 3));
        EventEnvelope<SamplePayload> second =
                EventEnvelope.of("service.updated", entityId, tenantId, 1, new SamplePayload("svc", 4));

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void differentEntityIdProducesADifferentEventIdForOtherwiseIdenticalPayload() {
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first =
                EventEnvelope.of("service.updated", UUID.randomUUID(), tenantId, 1, payload);
        EventEnvelope<SamplePayload> second =
                EventEnvelope.of("service.updated", UUID.randomUUID(), tenantId, 1, payload);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void correlationIdIsStillRandomPerCall() {
        UUID entityId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SamplePayload payload = new SamplePayload("svc", 3);

        EventEnvelope<SamplePayload> first = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);
        EventEnvelope<SamplePayload> second = EventEnvelope.of("service.updated", entityId, tenantId, 1, payload);

        assertThat(first.correlationId()).isNotEqualTo(second.correlationId());
    }
}
```

**Verify**: `./gradlew :shared:common:test` → exit 0, 4 new tests pass. Before this fix, `sameLogicalEventProducesTheSameEventId` would fail (that's the regression this plan closes — you can confirm this by temporarily reverting Step 1 and re-running, then reapplying Step 1; this manual check is optional but recommended if you want direct proof).

### Step 3: Confirm no downstream module breaks

Every producer listed in "Current state" calls `EventEnvelope.of(...)` unchanged — no call-site edits are expected. Run the full build across the four dependent modules to confirm.

**Verify**: `./gradlew :services:registry:build :services:ingestion:build :services:topology:build` → exit 0, all existing tests still pass (this includes `GraphNodeServiceTest`, `GraphNodeEventConsumerIT`, `RegistryServiceDiscoveryConsumerIT`, `SyncWorkerIT`, `CodeownersFlowIT` — all confirmed in recon to only read `env.eventId()` off a single envelope instance, never compare two, so none should need changes).

## Test plan

- New tests: `shared/common/src/test/java/io/cartogra/common/event/EventEnvelopeTest.java` (4 cases, listed in Step 2) — covers the determinism guarantee this plan adds, distinctness across payload/entity changes, and that `correlationId` (a genuinely per-attempt field, unlike `eventId`) stays random.
- No existing test file needs modification.
- Verification: `./gradlew :shared:common:test` → all pass, including the 4 new tests.

## Done criteria

Machine-checkable. ALL must hold:

- [ ] `./gradlew :shared:common:build` exits 0
- [ ] `./gradlew :shared:common:test` exits 0; `EventEnvelopeTest` exists with 4 passing tests
- [ ] `./gradlew :services:registry:build :services:ingestion:build :services:topology:build` exits 0 with no new test failures
- [ ] `grep -n "now.toString()" shared/common/src/main/java/io/cartogra/common/event/EventEnvelope.java` returns no matches
- [ ] No files outside the in-scope list are modified (`git status`)
- [ ] `plans/README.md` status row for plan 004 updated

## STOP conditions

Stop and report back (do not improvise) if:

- The code at `EventEnvelope.java` doesn't match the "Current state" excerpt (the codebase has drifted since this plan was written).
- Any existing test outside `EventEnvelopeTest` starts failing after Step 1 — that would mean some test *does* rely on wall-clock-derived non-determinism, which contradicts this plan's recon and needs the operator's judgment before proceeding.
- A payload type is found that is *not* a record (this plan's `String.valueOf(payload)` approach assumes structural `toString()`; a non-record payload with a default `Object.toString()` — `ClassName@hashcode` — would silently defeat the fix by being non-deterministic across JVM instances). If found, stop and report which producer passes it.

## Maintenance notes

- If a future producer needs to publish two envelopes for the *same* entity/tenant/version with the *same* payload content but wants them treated as genuinely distinct events (unlikely, but possible for something like a heartbeat), it must pass a distinguishing field inside the payload record itself (e.g., an explicit nonce field) — `EventEnvelope.of()` has no other seam for that after this change.
- This fix does not change ingestion's `sync.command` idempotency strategy (ADR-0014's concurrent-execution guard) — that mechanism doesn't use `eventId` at all and is unaffected.
- Any payload type added in the future must remain a record (or otherwise implement a structural, deterministic `toString()`) for this fix to hold — worth a one-line note in `CONTRIBUTING.md` or a payload-authoring convention doc if one exists, but that's a follow-up, not part of this plan.
