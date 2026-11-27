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
| **Provider** | An implementation of the `ScmProvider` SPI: `github` or `azuredevops` |
| **Service Discovered** | A Kafka event emitted when a repository or K8s Service warrants a registry entry |
| **Ownership Resolved** | A Kafka event emitted when a repository's CODEOWNERS (or K8s namespace label) maps to a team |
| **Tech Stack** | Set of detected build technologies from repository file presence (e.g., `java`, `spring-boot`, `go`) |
| **External ID** | Stable provider-scoped identifier for a repository or K8s Service; used by Registry for upsert deduplication |
| **Stale Job** | A Sync Job that has been in `RUNNING` state for > 30 minutes; reaped to `FAILED` by the stale-job reaper |
| **K8s Worker** | Optional component that watches Kubernetes Services/Endpoints/Namespaces for Cartogra-labeled tenants |
| **Sync Command** | Kafka message from Registry instructing Ingestion to start a sync job for a given connection |
| **Spec Discovered** | A Kafka event emitted when a sync finds an OpenAPI file at a probe path of a repository (5.8) |
| **Spec Path** | A repository-relative probe path for the OpenAPI file. Five built-in defaults, plus up to 10 custom paths per tenant that an admin adds; custom paths are probed first, the first hit wins |
| **Change Event** | (Phase 6, planned) A webhook delivery from the SCM that Cartogra accepts and acts on: a push to the default branch, or a repository created, renamed, archived, or deleted. Pushes to other branches and pull request events are not Change Events |
| **Repository Sync** | (Phase 6, planned) A sync of one repository, started by a Change Event. Different from a Sync Job, which scans all the repositories of a connection |
| **Webhook Reject** | (Phase 6, planned) A webhook delivery that Cartogra refuses, for example for a bad signature. Kept per connection as a time and a reason, never the payload |
| **Setup Path** | (Phase 6, planned) How a connection gets Change Events. *Manual*: the admin makes the webhook in the SCM. *Automatic*: Cartogra gets them with no hook to make (GitHub App) or makes the hooks itself (Azure DevOps, opt-in). See ADR-0034 |
| **App Installation** | (Phase 6, planned) The Cartogra GitHub App installed on one GitHub organization, on all or on selected repositories. It is an SCM Connection with the auth mode `app`, and it links to exactly one tenant |
| **Repository Id** | (Phase 6, planned) The stable id that the provider gives a repository (GitHub number, Azure DevOps GUID). It does not change when the repository is renamed or moved. It replaces the path as the External ID (ADR-0021, amended) |

---

## Core Responsibilities

1. **SCM sync** — on receiving a Sync Command, start a Sync Job, list repositories via the appropriate Provider, detect tech stack, emit `service.discovered` per repo, emit `ownership.resolved` per CODEOWNERS result
2. **Kubernetes watch** — continuously watch namespaces labeled `cartogra.io/tenant-id=<UUID>`; the label only opts in, and must equal the tenant that owns the cluster (mismatch → skipped). The tenant written is always the cluster's; emit `service.discovered` on Service ADD/MODIFY and `service.removed` on DELETE; derive health from Endpoints; after each full resync emit `service.resynced` with the set of present Services (no set when a list call fails)
3. **Sync Job tracking** — persist lifecycle state; idempotency guard (drop command if a RUNNING job exists for the same connection)
4. **Stale job reaper** — periodic task that marks RUNNING jobs older than 30 min as FAILED
5. **Result notification** — emit `sync.completed` after every job (success or failure)
6. **Spec discovery** (5.8) — per repo, probe the tenant's spec paths (custom, then defaults: `openapi.yaml`, `openapi.yml`, `openapi.json`, `api/openapi.yaml`, `docs/openapi.yaml`) and emit `spec.discovered` for the first file found. An empty file or one over 512 KiB is skipped with a WARN log and the counter `cartogra.ingestion.spec.skipped` (tag `reason`); a fetch error counts as no file. The probe order is read once per sync

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
| `providerType()` | `String` | `"github"` or `"azuredevops"` |
| `listRepositories(config)` | `List<ScmRepository>` | All non-archived repos |
| `getFileContents(config, repo, path)` | `Optional<String>` | Single file; used for CODEOWNERS |
| `getLastCommit(config, repo)` | `CommitInfo(sha, committedAt)` | HEAD commit of default branch |
| `resolveOwnership(config, repo)` | `OwnershipMap(ownerTeams, pathOwners)` | CODEOWNERS + PR label signals |

**Implementations**: `GitHubProvider` (PAT auth), `AzureDevOpsProvider` (PAT or service principal)

---

## Discovery rules

**Kubernetes health** (from Endpoints):

| Endpoints state | `healthStatus` |
|---|---|
| Resource absent or no subsets | UNKNOWN |
| All subsets have ready addresses only | HEALTHY |
| Some subsets have both ready and not-ready addresses | DEGRADED |
| All subsets have only not-ready addresses | UNHEALTHY |

**Tech stack** (SCM, per non-archived repo, additive: e.g. `["java","spring-boot"]`):

| File | Technology |
|---|---|
| `pom.xml` / `build.gradle` / `build.gradle.kts` | java (+ spring-boot if it contains `spring-boot`) |
| `package.json` | javascript |
| `go.mod` | go |
| `Cargo.toml` | rust |
| `requirements.txt` | python |
| `Dockerfile` FROM `eclipse-temurin:*` / `node:*` / `python:*` / `golang:*` / `rust:*` | java / javascript / python / go / rust |

---

## Use Cases

| Use Case | Trigger | Output |
|---|---|---|
| `ExecuteSyncUseCaseImpl` | Kafka `sync.command` | Creates SyncJob; iterates repos; emits `service.discovered` + `ownership.resolved` per repo; emits `sync.completed` |
| `TechStackDetector` | Called inside `ExecuteSync` per repo | Returns detected tech stack set from file-presence heuristics |
| `StaleJobReaperScheduler` | `@Scheduled` every minute | Marks RUNNING jobs > 30 min old as FAILED |
| `KubernetesWorker` | Fabric8 watch events | Emits `service.discovered` on K8s Service ADD/MODIFY, `service.removed` on DELETE, `service.resynced` after each full resync |

---

## Inbound Ports

| Channel | Event / Endpoint | Handler |
|---|---|---|
| Kafka | `cartogra.registry.sync.command` | `SyncCommandConsumer` → `ExecuteSyncUseCaseImpl` |
| K8s API server | Service/Endpoint/Namespace watch | `KubernetesWorker` (enabled via `ENABLE_K8S_WORKER=true`) |

| HTTP | `GET /api/v1/ingestion/spec-paths` | `SpecPathController` → `{defaults[], custom[]}` (any role) |
| HTTP | `PUT /api/v1/ingestion/spec-paths` | `{custom[]}` replaces only the custom list; `Roles.ADMIN_ONLY`, audited `spec_paths.updated`. A path must be relative, have no `..` and no glob characters, have ≤ 256 characters and not be a default; ≤ 10 entries, no duplicates; else 400 `VALIDATION_ERROR` |

Other HTTP endpoints: `/scm-connections`, `/k8s/clusters` and the webhook receivers (see `docs/api/ingestion.openapi.yaml`).

---

## Outbound Ports (Kafka produced)

| Topic | Trigger | Key payload fields |
|---|---|---|
| `cartogra.ingestion.service.changed` | `service.discovered`: per non-archived repo (SCM) or K8s Service ADD/MODIFY. `service.removed`: K8s Service DELETE. `service.resynced`: after a K8s resync, or with an empty set on a cluster delete/rename | discovered: `externalId`, `tenantId`, `connectionId`, `name`, `repositoryUrl`, `techStack`, `healthStatus`, `source`. removed: `tenantId`, `k8sCluster`, `k8sNamespace`, `name`. resynced: `tenantId`, `k8sCluster`, `observedAt`, `services[]` |
| `cartogra.ingestion.ownership.resolved` | Per repo after CODEOWNERS resolution | `externalId`, `tenantId`, `connectionId`, `ownerTeams[]` (bare team names — provider handle syntax stripped by `CodeownersHandles`), `pathOwners{}` (raw handles) |
| `cartogra.ingestion.spec.discovered` | Per repo with an OpenAPI file at a probe path (5.8) | key = `entityId` = UUIDv5 of `(tenant, repo, path)`; `tenantId`, `connectionId`, `repositoryFullName`, `filePath`, `contentSha256`, `contentB64`. No service id: Contract resolves it (ADR-0018) |
| `cartogra.ingestion.sync.completed` | After each Sync Job completes | `connectionId`, `tenantId`, `status`, `repositoriesSynced`, `errorMessage` |

Future topics (Phase 2/3):
- `cartogra.ingestion.dependency.observed` — OTel-derived dependency edges

---

## Database (Flyway V001–V002)

| Version | Description |
|---|---|
| V001 | Baseline (empty, schema setup) |
| V002 | `sync_jobs` table |
| V008 | `tenant_spec_paths` — custom spec probe paths per tenant (`paths TEXT[]`), RLS |

Flyway history table: `flyway_schema_history_ingestion`

---

## Context Relationships

| Neighbour | Relationship | Notes |
|---|---|---|
| Service Catalog (Registry) | Upstream (Published Language) → Downstream | Registry consumes `service.discovered` and `ownership.resolved` |
| Service Catalog (Registry) | Downstream (Conformist) ← Upstream | Registry sends `sync.command` to trigger scans |
| Contract | Upstream → Downstream | Produces `spec.discovered` (5.8); Contract resolves the repository to a service |
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
- ADR-0018 — Spec discovery transport: Kafka envelopes from Ingestion to Contract (implemented in 5.8)
