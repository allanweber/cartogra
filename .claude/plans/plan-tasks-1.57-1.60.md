# Plan: Tasks 1.57–1.60 — SCM Sync E2E (Revised)

**Branch**: `feat/1.57-1.60-scm-sync-e2e`

## Architectural shift

SCM connection management moves from **registry** to **ingestion**. Decisions from grill session:

| Decision | Choice |
|----------|--------|
| `services.connection_id` in registry | Stays as opaque UUID; `ServiceDiscoveredPayload` gains `providerType` so registry never calls ingestion |
| Public API path `/api/v1/scm-connections` | Kept; gateway rerouted to ingestion |
| `scm_webhooks` | Moves to ingestion (V003) alongside `scm_connections` (V002) |
| `webhook_secret` | Removed from `scm_connections`; lives only in `scm_webhooks` |
| Sync feedback loop | `ExecuteSyncUseCaseImpl` writes directly; `SyncCompletedConsumer` deleted |
| On-demand sync trigger | Moves to ingestion; `SyncCommandProducer` deleted from registry |
| `InternalScmConnectionController` | Never shipped; deleted |
| `RegistryClient` in ingestion | Eliminated entirely — no REST bridging needed |

---

## Already done on this branch (keep)

- `ScmProvider` SPI: `verifyWebhookSignature` + `isRelevantWebhookEvent` default methods ✓
- `GitHubProvider` + `AzureDevOpsProvider`: webhook method implementations ✓
- `ServiceDiscoveredPayload` (both ingestion + registry): `repoVisibility`, `archivedAt` ✓
- Registry `Service` domain + `JdbcServiceRepository`: `techStack VARCHAR(50)[]`, `repoVisibility`, `archivedAt` ✓
- Registry V005 in-place edit: `tech_stack VARCHAR(50)[]`, `repo_visibility`, `archived_at` ✓
- `RegistryServiceDiscoveryConsumer`: maps new payload fields ✓
- Gateway `SecurityConfig` + `application.yml`: webhook `permitAll` + ingestion route stub ✓
- `ErrorCodes`: `WEBHOOK_SIGNATURE_INVALID`, `WEBHOOK_CONNECTION_NOT_FOUND` ✓
- Ingestion `GlobalExceptionHandler` updates ✓
- `ExecuteSyncUseCaseImpl` + `KubernetesWorker`: updated for new payload fields ✓

---

## What to undo / delete from registry

| File | Action |
|------|--------|
| `services/registry/src/main/resources/db/migration/V004__create_scm_connections.sql` | Delete file |
| `services/registry/src/main/resources/db/migration/V007__create_scm_webhooks.sql` | Delete file |
| `services/registry/src/main/java/io/cartogra/registry/domain/ScmConnection.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/domain/exception/ScmConnectionNotFoundException.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/repository/ScmConnectionRepository.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/infrastructure/jdbc/JdbcScmConnectionRepository.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/CreateScmConnectionUseCase.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/UpdateScmConnectionUseCase.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/DeleteScmConnectionUseCase.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/FindScmConnectionUseCase.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/ListScmConnectionsUseCase.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/impl/CreateScmConnectionUseCaseImpl.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/impl/UpdateScmConnectionUseCaseImpl.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/impl/DeleteScmConnectionUseCaseImpl.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/impl/FindScmConnectionUseCaseImpl.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/usecase/impl/ListScmConnectionsUseCaseImpl.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/dto/CreateScmConnectionCommand.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/application/dto/UpdateScmConnectionCommand.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/controller/ScmConnectionController.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/controller/InternalScmConnectionController.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/CreateScmConnectionRequest.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/UpdateScmConnectionRequest.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/ScmConnectionResponse.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/SyncTimestampsRequest.java` | Delete (if created) |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/WebhookConfigResponse.java` | Delete (if created) |
| `services/registry/src/main/java/io/cartogra/registry/api/dto/DueConnectionResponse.java` | Delete (if created) |
| `services/registry/src/main/java/io/cartogra/registry/api/mapper/ScmConnectionMapper.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/infrastructure/kafka/SyncCompletedConsumer.java` | Delete |
| `services/registry/src/main/java/io/cartogra/registry/infrastructure/kafka/SyncCommandProducer.java` | Delete |

Flyway gap: V004 and V007 removed from registry. Flyway tolerates version gaps — no placeholder needed.

---

## Per-task implementation (revised)

### Task 1.57 — SCM CRUD stack moves to ingestion + sync feedback

**Schema (ingestion)**

`services/ingestion/src/main/resources/db/migration/V002__create_scm_connections.sql`:

```sql
CREATE TABLE scm_connections (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    provider              VARCHAR(50)  NOT NULL,
    config                JSONB        NOT NULL DEFAULT '{}',
    sync_scheduler        BOOLEAN      NOT NULL DEFAULT false,
    poll_interval_minutes INT          NOT NULL DEFAULT 15,
    next_sync_at          TIMESTAMPTZ,
    last_sync_at          TIMESTAMPTZ,
    last_sync_status      TEXT,
    webhook_enabled       BOOLEAN      NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);

CREATE INDEX ON scm_connections (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON scm_connections USING GIN (config);
CREATE INDEX ON scm_connections (next_sync_at)
    WHERE sync_scheduler = true AND deleted_at IS NULL;

ALTER TABLE scm_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scm_connections
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
```

Note: no `webhook_secret` column — secret lives in `scm_webhooks`.

`services/ingestion/src/main/resources/db/migration/V003__create_scm_webhooks.sql`:

```sql
CREATE TABLE scm_webhooks (
    id                UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         UUID          NOT NULL,
    scm_connection_id UUID          NOT NULL,
    provider          VARCHAR(50)   NOT NULL,
    external_id       VARCHAR(255),
    target_url        VARCHAR(2048) NOT NULL,
    webhook_secret    VARCHAR(2048),
    events            VARCHAR(100)[] NOT NULL DEFAULT '{}',
    status            VARCHAR(50)   NOT NULL DEFAULT 'active',
    last_received_at  TIMESTAMPTZ,
    error_message     VARCHAR(2000),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);

CREATE INDEX ON scm_webhooks (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON scm_webhooks (scm_connection_id) WHERE deleted_at IS NULL;

ALTER TABLE scm_webhooks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON scm_webhooks
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
```

**Domain + repository (ingestion)**

Copy `ScmConnection` record from registry into ingestion, removing `webhookSecret` field:

```
services/ingestion/src/main/java/io/cartogra/ingestion/domain/ScmConnection.java         — New
services/ingestion/src/main/java/io/cartogra/ingestion/domain/ScmWebhook.java             — New
services/ingestion/src/main/java/io/cartogra/ingestion/application/repository/ScmConnectionRepository.java — New
services/ingestion/src/main/java/io/cartogra/ingestion/application/repository/ScmWebhookRepository.java   — New
services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/jdbc/JdbcScmConnectionRepository.java — New
services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/jdbc/JdbcScmWebhookRepository.java   — New
```

`ScmConnection` record (ingestion):

```java
public record ScmConnection(
    UUID id, UUID tenantId, String provider, String config,
    boolean syncScheduler, int pollIntervalMinutes,
    @Nullable Instant nextSyncAt, @Nullable Instant lastSyncAt, @Nullable String lastSyncStatus,
    boolean webhookEnabled,
    Instant createdAt, Instant updatedAt, @Nullable Instant deletedAt
) {}
```

`ScmWebhook` record (ingestion):

```java
public record ScmWebhook(
    UUID id, UUID tenantId, UUID scmConnectionId, String provider,
    @Nullable String externalId, String targetUrl, @Nullable String webhookSecret,
    List<String> events, String status,
    @Nullable Instant lastReceivedAt, @Nullable String errorMessage,
    Instant createdAt, Instant updatedAt, @Nullable Instant deletedAt
) {}
```

`ScmConnectionRepository` methods:

```java
Optional<ScmConnection> findById(UUID tenantId, UUID id);
List<ScmConnection> findAll(UUID tenantId, int limit, int offset);
long count(UUID tenantId);
ScmConnection save(ScmConnection connection);
void softDelete(UUID tenantId, UUID id);
List<ScmConnection> findDue();                                       // scheduler
void updateSyncTimestamps(UUID id, Instant lastSyncAt, Instant nextSyncAt);  // scheduler
void updateSyncResult(UUID id, String status, Instant lastSyncAt);  // sync feedback
```

`ScmWebhookRepository` methods:

```java
Optional<ScmWebhook> findByConnectionId(UUID connectionId);  // webhook handler lookup
```

**CRUD use cases (ingestion)**

Ports + implementations — mirror the registry pattern exactly:

```
application/usecase/CreateScmConnectionUseCase + impl
application/usecase/UpdateScmConnectionUseCase + impl
application/usecase/DeleteScmConnectionUseCase + impl
application/usecase/FindScmConnectionUseCase + impl
application/usecase/ListScmConnectionsUseCase + impl
application/dto/CreateScmConnectionCommand
application/dto/UpdateScmConnectionCommand
```

**Controller + DTOs (ingestion)**

```
api/controller/ScmConnectionController.java   — mirrors registry version; path /scm-connections
api/dto/CreateScmConnectionRequest.java
api/dto/UpdateScmConnectionRequest.java
api/dto/ScmConnectionResponse.java            — no webhookSecret field (secret is write-only)
api/mapper/ScmConnectionMapper.java
```

`ScmConnectionController` endpoints:

```
GET    /scm-connections          → list (paginated)
POST   /scm-connections          → create
GET    /scm-connections/{id}     → find
PUT    /scm-connections/{id}     → update
DELETE /scm-connections/{id}     → soft delete
POST   /scm-connections/{id}/sync → trigger on-demand sync (publishes sync.command)
```

**`SyncCommandProducer` (moves to ingestion)**

Move `SyncCommandProducer` from registry to:

```
services/ingestion/src/main/java/io/cartogra/ingestion/infrastructure/kafka/SyncCommandProducer.java
```

Same implementation, same topic (`cartogra.registry.sync.command`), same `SyncCommandPayload`.

**Sync feedback (closes the loop)**

In `ExecuteSyncUseCaseImpl`, after the sync completes, call:

```java
scmConnectionRepository.updateSyncResult(connectionId, status, Instant.now());
```

No Kafka consumer needed. `SyncCompletedConsumer` in registry is deleted.

**`ServiceDiscoveredPayload` — add `providerType`**

Add `@Nullable String providerType` to both ingestion and registry copies of `ServiceDiscoveredPayload`. Registry stores it on `Service` → add `provider_type VARCHAR(50)` column to V005 in-place. This lets registry display "discovered from GitHub" without ever calling ingestion.

```java
// ingestion side — populate when publishing
new ServiceDiscoveredPayload(..., connection.provider(), ...);
```

---

### Task 1.58 — Periodic scheduler (direct DB, no RegistryClient)

The scheduler lives in ingestion and reads `scm_connections` directly — no REST calls.

**Files to create:**

| File | Notes |
|------|-------|
| `application/repository/AdvisoryLockRepository.java` | Port: `tryAcquireXactLock(long key)` |
| `infrastructure/jdbc/JdbcAdvisoryLockRepository.java` | `SELECT pg_try_advisory_xact_lock(:key)` against ingestion DB |
| `application/usecase/TriggerScheduledSyncUseCase.java` | Interface: `attemptForConnection(ScmConnection conn)` |
| `application/usecase/impl/TriggerScheduledSyncUseCaseImpl.java` | `@Transactional(REQUIRES_NEW)`: lock → publish → commit |
| `infrastructure/scheduled/SyncScheduler.java` | `@Scheduled` tick |

**`SyncScheduler.tick()`**:

```java
@Scheduled(fixedDelayString = "${ingestion.sync.poll-interval:PT15M}")
public void tick() {
    List<ScmConnection> due = scmConnectionRepository.findDue();
    for (ScmConnection conn : due) {
        try {
            triggerScheduledSync.attemptForConnection(conn);  // REQUIRES_NEW tx
            Instant now = Instant.now();
            Instant next = now.plus(conn.pollIntervalMinutes(), ChronoUnit.MINUTES);
            scmConnectionRepository.updateSyncTimestamps(conn.id(), now, next);
        } catch (Exception ex) {
            log.warn("Scheduler: failed for connection={}: {}", conn.id(), ex.getMessage());
        }
    }
}
```

`tick()` is NOT `@Transactional`. Each connection is its own boundary. `updateSyncTimestamps` is called outside the `REQUIRES_NEW` transaction — if it fails, next tick retries (at-least-once).

**`TriggerScheduledSyncUseCaseImpl`**:

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void attemptForConnection(ScmConnection conn) {
    long key = Long.parseUnsignedLong(conn.id().toString().replace("-","").substring(0,16), 16);
    if (!advisoryLockRepository.tryAcquireXactLock(key)) return;
    syncCommandProducer.publish(conn);  // publishes inside tx
}
```

**`application.yml` additions (ingestion)**:

```yaml
ingestion:
  sync:
    poll-interval: ${INGESTION_SYNC_POLL_INTERVAL:PT15M}
```

No `services.registry.base-url` needed — ingestion reads its own DB.

---

### Task 1.59 — Webhook controller (direct DB lookup from scm_webhooks)

**Webhook handler flow:**

```
POST /webhooks/{providerType}/{connectionId}
  → ProcessWebhookUseCaseImpl
      → scmWebhookRepository.findByConnectionId(connectionId)   // reads scm_webhooks
      → provider.verifyWebhookSignature(rawBody, headers, webhook.webhookSecret())
      → provider.isRelevantWebhookEvent(headers, rawBody)
      → syncCommandProducer.publish(connection)
```

**Files to create:**

| File | Notes |
|------|-------|
| `api/WebhookController.java` | `POST /webhooks/{providerType}/{connectionId}` |
| `application/usecase/ProcessWebhookUseCase.java` | Interface |
| `application/usecase/impl/ProcessWebhookUseCaseImpl.java` | Fetch → validate → publish |

No `RegistryClient`, no `WebhookConfig` DTO, no `InternalScmConnectionController`. The secret comes directly from `scm_webhooks.webhook_secret` via `ScmWebhookRepository`.

**`ProcessWebhookUseCaseImpl`**:

```java
public void process(String providerType, UUID connectionId, byte[] rawBody, Map<String,String> headers) {
    ScmProvider provider = providers.get(providerType);
    if (provider == null) throw new WebhookConnectionNotFoundException(providerType);

    ScmWebhook webhook = scmWebhookRepository.findByConnectionId(connectionId)
        .filter(w -> w.webhookEnabled() && w.deletedAt() == null)
        .orElseThrow(() -> new WebhookConnectionNotFoundException(connectionId));

    if (!provider.verifyWebhookSignature(rawBody, headers, webhook.webhookSecret()))
        throw new WebhookSignatureInvalidException(connectionId);

    if (!provider.isRelevantWebhookEvent(headers, new String(rawBody, UTF_8))) return;

    ScmConnection conn = scmConnectionRepository.findById(webhook.tenantId(), connectionId)
        .orElseThrow(() -> new WebhookConnectionNotFoundException(connectionId));
    syncCommandProducer.publish(conn);
}
```

Note: `ScmWebhook` needs a `webhookEnabled()` convenience — derive it from the parent `scm_connections.webhook_enabled`, or add `webhook_enabled` to the `scm_webhooks` lookup query via a JOIN.

Simpler: query `scm_connections` for `webhook_enabled`, then query `scm_webhooks` for the secret. Two queries, no JOIN needed in Spring Data JDBC.

**Gateway changes** (`services/gateway/src/main/resources/application.yml`):

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: ingestion-webhooks
          uri: ${INGESTION_URI:http://localhost:8085}
          predicates:
            - Path=/api/v1/ingestion/webhooks/**
          filters:
            - StripPrefix=3
          order: -20

        - id: ingestion-scm-connections
          uri: ${INGESTION_URI:http://localhost:8085}
          predicates:
            - Path=/api/v1/scm-connections/**
          filters:
            - StripPrefix=2
          order: -10

        - id: registry
          uri: ${REGISTRY_URI:http://localhost:8081}
          predicates:
            - Path=/api/v1/**
```

**`SecurityConfig`** — `/api/v1/scm-connections/**` stays authenticated (no change); `/api/v1/ingestion/webhooks/**` stays `permitAll` (already done).

**`WebhookControllerIT`** (5 scenarios, `@EmbeddedKafka` + Testcontainers — no WireMock needed):

1. Valid GitHub push + correct HMAC → 202, `sync.command` on Kafka
2. GitHub push + tampered body → 401, no Kafka
3. GitHub `ping` → 202, no Kafka
4. Valid AzDO `git.push` + correct basic-auth → 202, `sync.command` on Kafka
5. AzDO + wrong auth → 401, no Kafka

---

### Task 1.60 — Docs

| File | Action |
|------|--------|
| `docs/api/ingestion.openapi.yaml` | New — webhook endpoint + scm-connections CRUD |
| `docs/runbooks/deployment.md` | Append webhook setup + scheduled sync sections |
| `postman/ingestion.postman_collection.json` | New — webhook simulation requests |
| `postman/cartogra-local.postman_environment.json` | Add `webhookSecret` variable |
| `docs/diagrams/ingestion/scheduled-sync-sequence.puml` | New |
| `docs/diagrams/ingestion/webhook-sync-sequence.puml` | New |
| `docs/diagrams/ingestion/scm-connections-er.puml` | New — V002 + V003 ER |

---

## Tests

| Class | Where | Containers | Scenarios |
|-------|-------|-----------|-----------|
| `TriggerScheduledSyncUseCaseImplTest` | ingestion | none | lock miss → no publish; lock acquired → publish |
| `GitHubProviderWebhookTest` | ingestion | none | HMAC-SHA256 vector; push/ping/unknown |
| `AzureDevOpsProviderWebhookTest` | ingestion | none | basic-auth; event type filter |
| `SyncSchedulerIT` | ingestion | Postgres + EmbeddedKafka | due/not-due; dual-instance lock |
| `WebhookControllerIT` | ingestion | Postgres + EmbeddedKafka | 5 scenarios above |
| `SyncCompletedConsumerIT` | registry | — | **DELETE** — replaced by direct write test in `ExecuteSyncUseCaseImplIT` |

---

## Env vars delta

| Var | Service | Default |
|-----|---------|---------|
| `INGESTION_SYNC_POLL_INTERVAL` | ingestion | `PT15M` |
| `INGESTION_URI` | gateway | `http://localhost:8085` |

`REGISTRY_BASE_URL` in ingestion: **removed** — no longer needed.

---

## Rollback

```bash
docker compose down -v
docker compose up -d postgres
./gradlew :services:ingestion:flywayMigrate
./gradlew :services:registry:flywayMigrate
```

---

## Execution order

1. Delete registry SCM stack (migrations, domain, use cases, controller, Kafka classes)
2. Create ingestion V002 + V003 migrations
3. Move `ScmConnection` domain + JDBC repository to ingestion
4. Add `ScmWebhook` domain + repository to ingestion
5. Move CRUD use cases + controller + DTOs to ingestion
6. Move `SyncCommandProducer` to ingestion
7. Wire `ExecuteSyncUseCaseImpl` → direct `updateSyncResult` call (remove `SyncCompletedConsumer`)
8. Add `providerType` to `ServiceDiscoveredPayload` + V005 column in registry
9. Implement scheduler (1.58): `AdvisoryLockRepository`, `TriggerScheduledSyncUseCase`, `SyncScheduler`
10. Implement webhook controller (1.59): `ProcessWebhookUseCase`, `WebhookController`
11. Update gateway routes: add `ingestion-scm-connections` route
12. Docs + diagrams (1.60)
13. Mark 1.57, 1.58, 1.59, 1.60 done in `docs/execution-checklist.md`
