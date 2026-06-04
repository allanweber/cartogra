# Ingestion Context

**Service**: `services/ingestion` · Port `8085` · Phase 0/1 · **Live**

---

## Purpose

Ingestion is Cartogra's bridge between the external world (SCM providers, Kubernetes clusters) and the platform. It polls or reacts to external change signals, translates them into platform events, and publishes them to Kafka. It never writes to other services' databases — it only emits events that the Service Catalog and Contract contexts consume.

---

## Ubiquitous Language

| Term | Meaning in this context |
|---|---|
| **Sync Job** | A tracked unit of work representing one full scan of an SCM Connection; lifecycle: `PENDING → RUNNING → COMPLETED / FAILED` |
| **SCM Connection** | The configuration (credentials, provider type) for a connected source-code provider; ID sourced from Registry |
| **SCM Repository** | A repository returned by an SCM provider during a sync scan |
| **Provider** | An implementation of the `ScmProvider` SPI: `github` or `azure_devops` |
| **Service Discovered** | A Kafka event emitted when a repository or K8s Service warrants a registry entry |
| **Ownership Resolved** | A Kafka event emitted when a repository's CODEOWNERS (or K8s namespace label) maps to a team |
| **Tech Stack** | Set of detected build technologies from repository file presence (e.g., `java`, `spring-boot`, `go`) |
| **External ID** | Stable provider-scoped identifier for a repository or K8s Service; used by Registry for upsert deduplication |
| **Stale Job** | A Sync Job that has been in `RUNNING` state for > 30 minutes; reaped to `FAILED` by the stale-job reaper |
| **K8s Worker** | Optional component that watches Kubernetes Services/Endpoints/Namespaces for Cartogra-labeled tenants |
| **Sync Command** | Kafka message from Registry instructing Ingestion to start a sync job for a given connection |

---

## Core Responsibilities

1. **SCM sync** — on receiving a Sync Command, start a Sync Job, list repositories via the appropriate Provider, detect tech stack, emit `service.discovered` per repo, emit `ownership.resolved` per CODEOWNERS result
2. **Kubernetes watch** — continuously watch namespaces labeled `cartogra.io/tenant-id=<UUID>`; emit `service.discovered` on Service ADD/MODIFY; derive health from Endpoints
3. **Sync Job tracking** — persist lifecycle state; idempotency guard (drop command if a RUNNING job exists for the same connection)
4. **Stale job reaper** — periodic task that marks RUNNING jobs older than 30 min as FAILED
5. **Result notification** — emit `sync.completed` after every job (success or failure)

---

## Domain Model

```
SyncJob  ──  (tenant_id, connection_id, provider_type, status, counters)
```

**Aggregates:**

| Aggregate root | Table |
|---|---|
| `SyncJob` | `sync_jobs` |

`SyncJob` is the only domain entity owned by Ingestion. Everything else (SCM connections, service records) lives in Registry — Ingestion holds only the connection ID as a reference, never a hydrated object.

---

## SCM Provider SPI

`ScmProvider` interface (in `application/port/out/`):

| Method | Returns | Notes |
|---|---|---|
| `providerType()` | `String` | `"github"` or `"azure_devops"` |
| `listRepositories(config)` | `List<ScmRepository>` | All non-archived repos |
| `getFileContents(config, repo, path)` | `Optional<String>` | Single file; used for CODEOWNERS |
| `getLastCommit(config, repo)` | `CommitInfo(sha, committedAt)` | HEAD commit of default branch |
| `resolveOwnership(config, repo)` | `OwnershipMap(ownerTeams, pathOwners)` | CODEOWNERS + PR label signals |

**Implementations**: `GitHubProvider` (PAT auth), `AzureDevOpsProvider` (PAT or service principal)

---

## Use Cases

| Use Case | Trigger | Output |
|---|---|---|
| `ExecuteSyncUseCaseImpl` | Kafka `sync.command` | Creates SyncJob; iterates repos; emits `service.discovered` + `ownership.resolved` per repo; emits `sync.completed` |
| `TechStackDetector` | Called inside `ExecuteSync` per repo | Returns detected tech stack set from file-presence heuristics |
| `StaleJobReaperScheduler` | `@Scheduled` every minute | Marks RUNNING jobs > 30 min old as FAILED |
| `KubernetesWorker` | Fabric8 watch events | Emits `service.discovered` on K8s Service ADD/MODIFY |

---

## Inbound Ports

| Channel | Event / Endpoint | Handler |
|---|---|---|
| Kafka | `cartogra.registry.sync.command` | `SyncCommandConsumer` → `ExecuteSyncUseCaseImpl` |
| K8s API server | Service/Endpoint/Namespace watch | `KubernetesWorker` (enabled via `ENABLE_K8S_WORKER=true`) |

No HTTP endpoints for domain operations. The `GlobalExceptionHandler` exists only for the actuator/health paths.

---

## Outbound Ports (Kafka produced)

| Topic | Trigger | Key payload fields |
|---|---|---|
| `cartogra.ingestion.service.discovered` | Per non-archived repo (SCM) or K8s Service ADD/MODIFY | `externalId`, `tenantId`, `connectionId`, `name`, `repositoryUrl`, `techStack`, `healthStatus`, `source` |
| `cartogra.ingestion.ownership.resolved` | Per repo after CODEOWNERS resolution | `externalId`, `tenantId`, `connectionId`, `ownerTeams[]`, `pathOwners{}` |
| `cartogra.ingestion.sync.completed` | After each Sync Job completes | `connectionId`, `tenantId`, `status`, `repositoriesSynced`, `errorMessage` |

Future topics (Phase 2/3):
- `cartogra.ingestion.dependency.observed` — OTel-derived dependency edges
- `cartogra.ingestion.spec.updated` — OpenAPI/AsyncAPI spec file changes

---

## Database (Flyway V001–V002)

| Version | Description |
|---|---|
| V001 | Baseline (empty, schema setup) |
| V002 | `sync_jobs` table |

Flyway history table: `flyway_schema_history_ingestion`

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Upstream (Published Language) → Downstream | Registry consumes `service.discovered` and `ownership.resolved` |
| Service Catalog (Registry) | Downstream (Conformist) ← Upstream | Registry sends `sync.command` to trigger scans |
| Contract | Upstream → Downstream | Will produce `spec.updated` events (Phase 3) |
| Topology | Upstream → Downstream | Will produce `dependency.observed` events (Phase 2) |
| Shared Kernel | Shared Kernel | `EventEnvelope`, `SyncCommandPayload`, `ErrorCodes` |

---

## Key Files

| Path | Role |
|---|---|
| `src/main/java/.../application/port/out/ScmProvider.java` | SPI interface for SCM providers |
| `src/main/java/.../infrastructure/scm/github/GitHubProvider.java` | GitHub implementation |
| `src/main/java/.../infrastructure/scm/azuredevops/AzureDevOpsProvider.java` | Azure DevOps implementation |
| `src/main/java/.../infrastructure/k8s/KubernetesWorker.java` | Fabric8 Kubernetes watch |
| `src/main/java/.../application/usecase/ExecuteSyncUseCaseImpl.java` | Sync orchestration |
| `src/main/java/.../application/usecase/TechStackDetector.java` | File-presence tech detection |
| `src/main/java/.../infrastructure/scheduled/StaleJobReaperScheduler.java` | Stale job reaper |
| `src/main/resources/db/migration/` | V001–V002 Flyway scripts |
| `src/main/resources/application.yml` | DB, Kafka, K8s, OTel config |

---

## ADRs

- ADR-0002 — SCM Provider abstraction (SPI pattern)
- ADR-0014 — Sync command idempotency: drop duplicate if RUNNING; stale reaper for hung jobs
- ADR-0016 — OTel span worker ingestion for observed dependency edges (Phase 2)
- ADR-0018 — Spec discovery transport: Kafka envelopes from Ingestion to Contract (Phase 3)
