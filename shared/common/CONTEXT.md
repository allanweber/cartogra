# Shared Kernel — shared:common

**Module**: `shared/common` · Gradle dep `:shared:common` · **Live**

---

## Purpose

The Shared Kernel contains the narrow set of types that must be identical across all JVM services: the Kafka event envelope, HTTP response envelope, typed identity primitives, and error codes. It has **zero Spring dependencies** — plain Java 25 only. Any Spring import here would violate the hexagonal architecture by coupling the kernel to a framework.

---

## Constraint

> **NEVER add Spring (or any framework) dependencies to `shared:common`.**
> This module must compile with plain `javac` and no Spring on the classpath.

---

## Contents

### Event envelope (`io.cartogra.common.event`)

| Class | Purpose |
|---|---|
| `EventEnvelope<P>` | Generic Kafka message wrapper; factory method `of(eventType, entityId, tenantId, version, payload)` generates `eventId` via UUIDv5 |
| `UuidV5` | Deterministic UUID-v5 generation (SHA-1 namespace + name); used to compute `eventId` — suitable as a dedup key but not currently read by any consumer |
| `SyncCommandPayload` | Payload record for `cartogra.registry.sync.command`; fields: `connectionId`, `tenantId`, `providerType`, `connectionConfig` (Map) |

**`EventEnvelope<P>` fields:**

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | UUIDv5 salted with the publish-time instant — unique per `of()` call, but stable across Kafka-level redelivery of the same message (redelivery resends the already-serialized `eventId`, it isn't recomputed). Suitable as a redelivery dedup key, but **no consumer currently reads or checks it** — it is not an active idempotency mechanism today. |
| `eventType` | String | `domain.entity.action` e.g. `service.registered` |
| `entityId` | UUID | Primary entity |
| `tenantId` | UUID | Tenant isolation |
| `timestamp` | Instant | ISO-8601 |
| `version` | int | Payload schema version; increment on breaking payload change |
| `correlationId` | UUID | Trace correlation across async hops |
| `payload` | P | Domain-specific payload record |

### HTTP envelope (`io.cartogra.common.api`)

| Class | Purpose |
|---|---|
| `ApiResponse<T>` | Success envelope: `data` + `traceId` |
| `ApiError` | Error detail: `code` + `message` |
| `ApiErrorResponse` | Error envelope: `error` (ApiError) + `traceId` |
| `ErrorCodes` | String constants: `BAD_REQUEST`, `NOT_FOUND`, `VALIDATION_ERROR`, `CONFLICT`, `UNAUTHORIZED`, `RATE_LIMITED`, `INTERNAL_ERROR`, `SCM_PROVIDER_ERROR`, `SCM_PROVIDER_NOT_SUPPORTED`, `SYNC_JOB_NOT_FOUND` |
| `PageResult<T>` | Pagination wrapper: `items`, `total`, `limit`, `offset` |

### Identity primitives (`io.cartogra.common.identity`)

| Class | Purpose |
|---|---|
| `TenantId` | Typed UUID value object for tenant isolation |
| `ServiceId` | Typed UUID value object for service references |
| `SystemActors` | UUID constant for automated operations (e.g. CODEOWNERS auto-assignment); avoids magic UUIDs in code |

---

## What Does NOT Belong Here

- Spring beans, annotations, or autoconfiguration
- Infrastructure code (JDBC, Kafka, HTTP clients)
- Domain logic specific to a single service
- Mutable shared state

If a class needs Spring, it belongs in the service that uses it.

---

## Consumers

Every JVM service adds `:shared:common` to its Gradle dependencies:

```kotlin
// build.gradle.kts (each service)
dependencies {
    implementation(project(":shared:common"))
}
```

The frontend has its own parallel types in `src/lib/api.ts` (`ApiError`) — it does not share this module.

---

## Context Relationships

This is a **Shared Kernel** in DDD terms. All JVM bounded contexts depend on it. Changes here are breaking changes for all consumers — treat with care.
