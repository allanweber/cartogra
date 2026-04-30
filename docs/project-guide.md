# Cartogra — Implementation Guide

## Companion to the Project Scope Definition

This document covers the practical implementation details needed to start building Cartogra. It answers the "how" questions that the scope document intentionally leaves to this companion.

---

## 1. Monorepo Structure

```
cartogra/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                    # PR pipeline: lint → test → build → scan
│   │   ├── deploy-staging.yml        # Deploy to staging on merge to main
│   │   ├── deploy-production.yml     # Manual promotion from staging
│   │   └── release.yml               # Tag-based release pipeline
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.md
│   │   ├── feature_request.md
│   │   └── architecture_discussion.md
│   ├── PULL_REQUEST_TEMPLATE.md
│   └── CODEOWNERS
│
├── docs/
│   ├── adr/
│   │   ├── README.md                 # ADR index table
│   │   ├── TEMPLATE.md
│   │   ├── ADR-0001-postgresql-over-graph-database.md
│   │   └── ...
│   ├── architecture/
│   │   ├── system-overview.md
│   │   ├── data-model.md
│   │   ├── kafka-topics.md
│   │   └── diagrams/                 # Generated and hand-crafted diagrams
│   ├── api/
│   │   ├── gateway.openapi.yaml
│   │   ├── registry.openapi.yaml
│   │   ├── topology.openapi.yaml
│   │   ├── contract.openapi.yaml
│   │   └── intelligence.openapi.yaml
│   └── runbooks/
│       ├── local-development.md
│       ├── deployment.md
│       └── incident-response.md
│
├── services/
│   ├── gateway/
│   │   ├── src/main/java/dev/cartogra/gateway/
│   │   ├── src/main/resources/application.yml
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   │
│   ├── registry/
│   │   ├── src/main/java/dev/cartogra/registry/
│   │   │   ├── api/                  # REST controllers
│   │   │   ├── domain/               # Entities, value objects, domain events
│   │   │   ├── application/          # Use cases / application services
│   │   │   ├── infrastructure/       # DB repos, Kafka producers, SCM clients
│   │   │   │   ├── persistence/
│   │   │   │   ├── messaging/
│   │   │   │   └── scm/
│   │   │   │       ├── ScmProvider.java          # SPI interface
│   │   │   │       ├── GitHubProvider.java
│   │   │   │       └── AzureDevOpsProvider.java
│   │   │   └── config/               # Spring config, beans
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── db/migration/         # Flyway migrations
│   │   │       ├── V001__create_tenants.sql
│   │   │       ├── V002__create_teams.sql
│   │   │       ├── V003__create_users.sql
│   │   │       ├── V004__create_scm_connections.sql
│   │   │       ├── V005__create_services.sql
│   │   │       └── V006__create_services_history.sql
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   │
│   ├── topology/
│   │   ├── src/main/java/dev/cartogra/topology/
│   │   │   ├── api/
│   │   │   ├── domain/
│   │   │   ├── application/
│   │   │   ├── infrastructure/
│   │   │   │   ├── persistence/
│   │   │   │   ├── messaging/
│   │   │   │   └── otel/             # OpenTelemetry span ingestion
│   │   │   └── config/
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── db/migration/
│   │   │       ├── V001__create_dependencies.sql
│   │   │       ├── V002__create_dependency_graph_view.sql
│   │   │       └── V003__create_dependency_drifts.sql
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   │
│   ├── contract/
│   │   ├── src/main/java/dev/cartogra/contract/
│   │   │   ├── api/
│   │   │   ├── domain/
│   │   │   ├── application/
│   │   │   │   ├── compatibility/    # Breaking change detection engine
│   │   │   │   └── outbox/           # Outbox relay publisher
│   │   │   ├── infrastructure/
│   │   │   └── config/
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   └── db/migration/
│   │   │       ├── V001__create_api_contracts.sql
│   │   │       ├── V002__create_contract_consumers.sql
│   │   │       ├── V003__create_contract_versions.sql
│   │   │       ├── V004__create_contract_checks.sql
│   │   │       └── V005__create_outbox_events.sql
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   │
│   ├── intelligence/
│   │   ├── src/main/java/dev/cartogra/intelligence/
│   │   │   ├── api/
│   │   │   ├── domain/
│   │   │   ├── application/
│   │   │   │   ├── nlquery/          # Natural language → SQL translation
│   │   │   │   ├── antipattern/      # Pattern detection algorithms
│   │   │   │   └── digest/           # Weekly digest generation
│   │   │   ├── infrastructure/
│   │   │   │   ├── ai/              # Claude API client wrapper
│   │   │   │   └── persistence/
│   │   │   └── config/
│   │   ├── src/main/resources/
│   │   │   ├── application.yml
│   │   │   ├── prompts/             # Externalized prompt templates
│   │   │   │   ├── nl-query-system.txt
│   │   │   │   ├── anti-pattern-analysis.txt
│   │   │   │   └── digest-generation.txt
│   │   │   └── db/migration/
│   │   │       ├── V001__create_analysis_runs.sql
│   │   │       ├── V002__create_anti_pattern_findings.sql
│   │   │       └── V003__create_nl_query_log.sql
│   │   ├── src/test/
│   │   ├── Dockerfile
│   │   └── build.gradle.kts
│   │
│   └── ingestion/
│       ├── src/main/java/dev/cartogra/ingestion/
│       │   ├── workers/
│       │   │   ├── GitHubSyncWorker.java
│       │   │   ├── AzureDevOpsSyncWorker.java
│       │   │   ├── KubernetesSyncWorker.java
│       │   │   └── OtelSpanWorker.java
│       │   ├── webhook/              # Webhook payload handlers
│       │   ├── infrastructure/
│       │   └── config/
│       ├── src/main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       │       └── V001__create_sync_jobs.sql
│       ├── src/test/
│       ├── Dockerfile
│       └── build.gradle.kts
│
├── shared/
│   ├── common/                       # Shared DTOs, event envelopes, exceptions
│   │   ├── src/main/java/dev/cartogra/common/
│   │   │   ├── events/              # Event envelope, base event classes
│   │   │   │   ├── EventEnvelope.java
│   │   │   │   ├── DomainEvent.java
│   │   │   │   └── EventMetadata.java
│   │   │   ├── model/               # Shared value objects (TenantId, ServiceId, etc.)
│   │   │   ├── security/            # TenantContext, AuthPrincipal
│   │   │   └── exception/           # Shared exception hierarchy
│   │   └── build.gradle.kts
│   │
│   └── test-support/                 # Shared test utilities
│       ├── src/main/java/dev/cartogra/test/
│       │   ├── KafkaTestSupport.java
│       │   ├── PostgresTestSupport.java
│       │   └── TestDataBuilder.java
│       └── build.gradle.kts
│
├── frontend/
│   ├── src/
│   │   ├── routes/                  # TanStack Router file-based routes
│   │   │   ├── __root.tsx           # Root layout + providers (QueryClient, auth)
│   │   │   ├── index.tsx            # Catalog landing
│   │   │   ├── catalog/             # Catalog feature routes
│   │   │   ├── graph/               # Dependency graph routes
│   │   │   ├── contracts/           # Contract hub routes
│   │   │   ├── intelligence/        # Intelligence routes
│   │   │   └── operations/          # Operations routes
│   │   ├── components/
│   │   │   ├── ui/                   # shadcn/ui components
│   │   │   ├── catalog/             # Service catalog views
│   │   │   ├── graph/               # D3 dependency graph
│   │   │   ├── contracts/           # Contract hub views
│   │   │   ├── intelligence/        # NL query + anti-pattern feed
│   │   │   ├── operations/          # Real-time event stream
│   │   │   ├── tables/              # TanStack Table wrappers and feature tables
│   │   │   └── forms/               # TanStack Forms field groups and flows
│   │   ├── hooks/
│   │   ├── stores/                  # Zustand stores
│   │   ├── api/                     # TanStack Query hooks + API client
│   │   ├── types/
│   │   ├── router.tsx               # TanStack Router configuration
│   │   ├── routeTree.gen.ts         # Generated route tree
│   │   ├── entry-client.tsx
│   │   └── entry-server.tsx
│   ├── public/
│   ├── e2e/                         # Playwright tests
│   ├── package.json
│   ├── tsconfig.json
│   ├── tailwind.config.ts
│   ├── vite.config.ts
│   ├── app.config.ts                # TanStack Start runtime config
│   └── Dockerfile
│
├── ci-extensions/
│   ├── github-action/               # cartogra/contract-check GitHub Action
│   │   ├── action.yml
│   │   ├── src/
│   │   └── Dockerfile
│   └── azure-pipelines-task/        # Azure DevOps Pipeline Extension
│       ├── task.json
│       ├── src/
│       └── vss-extension.json
│
├── infra/
│   ├── docker-compose/
│   │   ├── docker-compose.yml        # Full local stack
│   │   ├── docker-compose.dev.yml    # Dev overrides (hot-reload, debug ports)
│   │   └── docker-compose.test.yml   # CI test overrides
│   ├── helm/
│   │   ├── cartogra/                # Umbrella chart
│   │   ├── gateway/
│   │   ├── registry/
│   │   ├── topology/
│   │   ├── contract/
│   │   ├── intelligence/
│   │   └── ingestion/
│   └── terraform/
│       ├── modules/
│       │   ├── kubernetes/
│       │   ├── database/
│       │   ├── kafka/
│       │   └── redis/
│       ├── environments/
│       │   ├── staging/
│       │   └── production/
│       └── main.tf
│
├── seed/
│   ├── seed-data.json                # The "Acme Fintech" demo organization
│   ├── seed-loader/                  # Script that applies seed data via APIs
│   │   ├── src/
│   │   └── package.json
│   └── README.md
│
├── build.gradle.kts                  # Root build file
├── settings.gradle.kts               # Module declarations
├── gradle.properties
├── .editorconfig
├── .gitignore
├── LICENSE
├── README.md
└── CONTRIBUTING.md
```

## 1.1 Documentation Map

- `docs/adr/README.md` is the ADR index; `docs/adr/TEMPLATE.md` is the template for future decisions.
- `docs/adr/ADR-0001-postgresql-over-graph-database.md` and `docs/adr/ADR-0002-scm-provider-abstraction.md` record the first accepted architecture decisions.
- `docs/architecture/system-overview.md`, `docs/architecture/data-model.md`, and `docs/architecture/kafka-topics.md` are the canonical architecture references for boundaries, persistence, and event topology.
- `docs/api/gateway.openapi.yaml`, `docs/api/registry.openapi.yaml`, `docs/api/topology.openapi.yaml`, `docs/api/contract.openapi.yaml`, and `docs/api/intelligence.openapi.yaml` are the API-first service contracts and should be updated in the service phases that implement them.
- `docs/runbooks/local-development.md`, `docs/runbooks/deployment.md`, and `docs/runbooks/incident-response.md` are the operational entry points for contributors, deploys, and incidents.

### Build System (Gradle Multi-Module)

Frontend install/run, dependency setup for TanStack Start/Query/Router/Table/Forms, and the **UI design workflow** (shape → craft loop with the `impeccable` skill, DESIGN.md/PRODUCT.md context files, phase-by-phase screen list) are documented in `docs/frontend-setup.md`.

Backend install/run and local infrastructure setup is documented in `docs/backend-setup.md`.

```kotlin
// settings.gradle.kts
rootProject.name = "cartogra"

include(
    "shared:common",
    "shared:test-support",
    "services:gateway",
    "services:registry",
    "services:topology",
    "services:contract",
    "services:intelligence",
    "services:ingestion"
)
```

Each service module depends on `shared:common`. Test configurations depend on `shared:test-support`. Services never depend on each other — they communicate only through Kafka and REST.

### Package Structure Convention (per service)

Each service follows hexagonal architecture (ports & adapters):

- `api/` — inbound adapters: REST controllers, webhook handlers
- `domain/` — core business logic: entities, value objects, domain events, domain services. Zero framework dependencies.
- `application/` — use cases: orchestrate domain logic, define ports (interfaces for outbound dependencies)
- `infrastructure/` — outbound adapters: database repositories, Kafka producers/consumers, external API clients
- `config/` — Spring configuration, bean wiring, security config

---

## 2. API Contracts (Core Endpoints)

### Gateway Service

The gateway handles **MVP authentication** (login, OAuth callbacks, cookie and Bearer token issuance/validation), **rate limiting**, and trace propagation, then proxies to downstream services. **Domain CRUD and business rules stay in domain services** — not in the gateway.

```
# Auth (see §3 — local + OTP, Google, GitHub, tenant OIDC)
POST   /auth/register                 # Start registration (email + password) → sends OTP via Resend
POST   /auth/verify-email             # Confirm OTP, activate account
POST   /auth/login                    # Local email + password
POST   /auth/oauth/{provider}/start   # Begin OAuth (google | github | tenant oidc slug)
POST   /auth/oauth/{provider}/callback
POST   /auth/token                    # Exchange OAuth2 code for session/JWT (if used)
POST   /auth/refresh                  # Refresh session/JWT
POST   /auth/logout
GET    /auth/userinfo                 # Current user profile

# All routes below are proxied to the appropriate service
# with tenant context and auth principal injected as headers
/api/v1/services/**       → Registry Service
/api/v1/teams/**          → Registry Service
/api/v1/connections/**    → Registry Service
/api/v1/dependencies/**   → Topology Service
/api/v1/graph/**          → Topology Service
/api/v1/contracts/**      → Contract Service
/api/v1/checks/**         → Contract Service
/api/v1/intelligence/**   → Intelligence Service
/api/v1/sync/**           → Ingestion Service (via Registry)
```

### Registry Service — `/api/v1`

```yaml
# Services
GET    /services                      # List services (paginated, filterable)
  query: team_id, health_status, tech_stack, tag, scm_provider, search, orphaned=true
  response: { items: Service[], total: int, page: int }

POST   /services                      # Register service manually
GET    /services/{id}                 # Get service detail
PUT    /services/{id}                 # Update service
DELETE /services/{id}                 # Soft delete

GET    /services/{id}/history         # Temporal history of changes
  query: since, until
  response: { snapshots: ServiceSnapshot[] }

GET    /services/{id}/history/at      # Point-in-time state
  query: timestamp
  response: ServiceSnapshot

# Ownership
PUT    /services/{id}/owner           # Assign/change owner team
  body: { team_id: uuid }

GET    /services/orphaned             # List services with no owner

# Teams
GET    /teams                         # List teams
POST   /teams                         # Create team
GET    /teams/{id}                    # Get team with its services
PUT    /teams/{id}                    # Update team
DELETE /teams/{id}                    # Soft delete

# SCM Connections
GET    /connections                    # List SCM connections
POST   /connections                   # Create connection (GitHub or Azure DevOps)
  body: { provider: "GITHUB"|"AZURE_DEVOPS", name: string, config: object }
GET    /connections/{id}              # Get connection detail + sync status
PUT    /connections/{id}              # Update connection config
DELETE /connections/{id}              # Remove connection
POST   /connections/{id}/sync         # Trigger manual sync
GET    /connections/{id}/sync-history # List past sync jobs

# Health
GET    /services/{id}/health          # Current health detail
GET    /health/summary                # Org-wide health overview
  response: { healthy: int, degraded: int, unhealthy: int, unknown: int }
```

### Topology Service — `/api/v1`

```yaml
# Dependencies
GET    /dependencies                  # List all dependencies (filterable)
  query: source_id, target_id, type (DECLARED|OBSERVED), protocol

POST   /dependencies                  # Declare a dependency manually
DELETE /dependencies/{id}             # Remove dependency

# Graph
GET    /graph                         # Full dependency graph for the tenant
  query: type (DECLARED|OBSERVED|BOTH)
  response: { nodes: GraphNode[], edges: GraphEdge[] }

GET    /graph/impact/{service_id}     # Blast radius analysis
  query: max_depth (default 10)
  response: { affected: AffectedService[], total_affected: int, max_depth_reached: int }

GET    /graph/spof                    # Single points of failure
  response: { findings: SpofFinding[] }

GET    /graph/cycles                  # Circular dependencies
  response: { cycles: ServiceId[][] }

# Drift
GET    /drifts                        # Unresolved dependency drifts
  query: resolved=false
  response: { drifts: DependencyDrift[] }

PUT    /drifts/{id}/resolve           # Mark drift as resolved
  body: { resolution_note: string }
```

### Contract Service — `/api/v1`

```yaml
# Contracts
GET    /contracts                     # List API contracts
  query: service_id, spec_type, compat_status, deprecated

POST   /contracts                     # Register contract manually
  body: { service_id: uuid, name: string, spec_type: "OPENAPI_3"|"ASYNCAPI_2",
          version: string, spec_content: object }

GET    /contracts/{id}                # Get contract detail
PUT    /contracts/{id}                # Update contract (publishes new version)
DELETE /contracts/{id}                # Soft delete (deprecate)

GET    /contracts/{id}/versions       # Version history
GET    /contracts/{id}/consumers      # Who consumes this contract

# Compatibility Checks
POST   /contracts/{id}/check          # Trigger compatibility check against previous version
  body: { new_spec_content: object, new_version: string }
  response: { check_id: uuid, is_breaking: bool, changes: Change[] }

GET    /checks                        # List pending/recent checks
  query: status (PENDING|APPROVED|BLOCKED), contract_id

GET    /checks/{id}                   # Check detail with structured diff
PUT    /checks/{id}/approve           # Approve a breaking change
  body: { note: string }
PUT    /checks/{id}/block             # Block a breaking change

# Compatibility Matrix
GET    /contracts/matrix               # Full compatibility matrix
  response: { producers: ProducerRow[] }
  # Each row: { service: Service, contracts: [{ contract: Contract,
  #   consumers: [{ service: Service, status: "COMPATIBLE"|"BREAKING"|"UNKNOWN" }] }] }

# CI Integration (called by GitHub Action / Azure Pipelines Task)
POST   /ci/check                      # Headless check for CI pipelines
  auth: Tenant-scoped API key only (v1), e.g. header X-Cartogra-Api-Key (final name in OpenAPI)
  body: { repo_url: string, spec_path: string, spec_content: object,
          pr_url: string, ci_provider: string, ci_run_url: string }
  response: { passed: bool, check_id: uuid, changes: Change[], blocking: bool }
  # Wrapped in global JSON envelope { data: {...}, traceId } per §2 Common Response Patterns
```

### Intelligence Service — `/api/v1`

```yaml
# Natural Language Queries
POST   /intelligence/query            # Ask a question in natural language
  body: { query: string }
  response: { answer: string, data: object|null, generated_sql: string|null,
              query_id: uuid }

POST   /intelligence/query/{id}/feedback  # Rate a query result
  body: { feedback: "helpful"|"not_helpful" }

# Analysis
POST   /intelligence/analyze          # Trigger an analysis
  body: { type: "anti_pattern_scan"|"health_score"|"digest"|"impact_analysis",
          params: object }
  response: { run_id: uuid, status: "PENDING" }

GET    /intelligence/analyze/{run_id} # Get analysis result
  response: AnalysisRun

# Anti-Pattern Findings
GET    /intelligence/findings         # List findings
  query: severity, pattern_type, resolved=false
  response: { findings: AntiPatternFinding[] }

PUT    /intelligence/findings/{id}/acknowledge
PUT    /intelligence/findings/{id}/resolve

# Health Score
GET    /intelligence/health-score     # Latest health score
  response: { score: decimal, trend: "UP"|"DOWN"|"STABLE",
              breakdown: CategoryScore[], generated_at: timestamp }

# Digest
GET    /intelligence/digests          # List generated digests
GET    /intelligence/digests/latest   # Latest weekly digest
```

### Common Response Patterns

**Applies to Cartogra Java / Spring REST APIs only** (gateway + registry, topology, contract, intelligence, ingestion HTTP). **Webhook endpoints** use provider payloads, not this envelope. Nested `data` payloads (e.g. lists) stay inside `data`; **`traceId` is always at the top level** next to `data` or `error`.

**`traceId` format:** Always the **OpenTelemetry trace id**: exactly **32 lowercase hex characters** (example below). It matches `SpanContext.getTraceId()` and the trace id segment inside W3C **`traceparent`**. Use the same value in logs and when searching Grafana/Jaeger/Tempo.

```json
// Success (example: paginated list inside data)
{
  "data": {
    "items": [],
    "total": 142,
    "page": 1,
    "page_size": 20,
    "has_next": true
  },
  "traceId": "7f89e2a1b3c4d5e6f708192a3b4c5d6e"
}

// Success (scalar or object)
{
  "data": { "id": "uuid", "name": "payment-processor" },
  "traceId": "7f89e2a1b3c4d5e6f708192a3b4c5d6e"
}

// Error — same error shape for all endpoints; traceId always present
{
  "error": {
    "code": "CONTRACT_BREAKING_CHANGE",
    "message": "Breaking change detected: required field 'idempotency_key' added",
    "details": { "check_id": "uuid", "affected_consumers": 3 }
  },
  "traceId": "7f89e2a1b3c4d5e6f708192a3b4c5d6e"
}
```

**Headers:** Clients SHOULD send W3C **`traceparent`**. Responses SHOULD include **`X-Trace-Id`**: the **32-hex** trace id only (same as body `traceId`, not the full `traceparent` string). All timestamps in payloads are ISO 8601 UTC; IDs are UUIDs unless otherwise specified.

OpenAPI descriptions for each **Spring** service should reference this envelope as the default response schema for JSON APIs.

---

## 3. Authentication & Authorization

### Auth flows (production intent)

1. **Local email + password**
   - Register with email, password, and display name → server creates a **pending** user, sends **OTP** via **Resend** → user submits OTP → `email_verified_at` set, account active.
   - Login with email + password only after verification (policy: unverified accounts cannot access tenant data).

2. **SSO — Google or GitHub**
   - Standard OAuth2 authorization code flow through the gateway; on success, link or create `users` row with `auth_provider` `google` or `github` and provider `sub` as `auth_subject`. **Default:** any Google account may use Sign in with Google.

3. **Company / tenant OAuth (workforce)**
   - Per-tenant **OAuth2/OIDC** (Azure Entra, Okta, generic OIDC): admin configures issuer, client id, and scopes; users in that tenant authenticate through the IdP. Maps to same JWT/session model as other methods.

4. **Session transport (both supported)**
   - **Browsers:** **httpOnly, Secure, SameSite** session or JWT **cookie** issued by the **gateway** (MVP — no separate auth service).
   - **Non-browser REST clients:** **`Authorization: Bearer <access token>`** after login or token exchange, validated by the gateway the same way as the cookie session.

5. **CI and other automation**
   - Pipelines call **`/ci/check`** with a **tenant-scoped API key** only (header such as **`X-Cartogra-Api-Key`** — confirm in OpenAPI). Keys live in GitHub Actions secrets / Azure DevOps secret variables; rotate from Cartogra admin.

### Diagram (high level)

```
Browser → Gateway → (Resend for OTP | Google | GitHub | Tenant OIDC) → session/JWT cookie
Gateway → downstream services: propagate trace + tenant + principal headers
```

### For the Live Demo

The demo may emphasize **GitHub** or **Google** for speed, but the product supports **local + OTP** and **tenant OIDC** as above. Visitors can still browse as guest (read-only, pre-seeded "Acme Fintech" tenant) where that mode is enabled.

### JWT Structure

```json
{
  "sub": "user-uuid",
  "tenant_id": "tenant-uuid",
  "email": "user@example.com",
  "role": "admin",
  "team_id": "team-uuid",
  "provider": "github",
  "iat": 1700000000,
  "exp": 1700003600
}
```

### RBAC Model

| Role | Services | Teams | Connections | Contracts | Intelligence | Admin |
|------|----------|-------|-------------|-----------|-------------|-------|
| **viewer** | Read | Read | Read | Read | Query | — |
| **member** | Read, Update own team's | Read, Update own | Read | Read, Register, Check | Query, Feedback | — |
| **admin** | Full CRUD | Full CRUD | Full CRUD | Full CRUD, Approve/Block | Full access | User mgmt, Settings |

### Multi-Tenant Isolation

- Every API request includes tenant context extracted from JWT at the Gateway
- Tenant ID is injected as a request header: `X-Tenant-Id`
- Every database query includes `WHERE tenant_id = :tenantId`
- PostgreSQL Row-Level Security (RLS) as a safety net:

```sql
ALTER TABLE services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
```

- Kafka messages are keyed by entity ID, but the envelope always includes `tenant_id` for consumer-side filtering
- Redis keys are prefixed with tenant ID: `tenant:{id}:service:{id}:health`

### Secrets Management

- **Local dev:** Environment variables via `.env` file (gitignored)
- **Staging/Production:** Kubernetes Secrets, optionally backed by:
  - Azure Key Vault (for Azure-hosted deployments)
  - HashiCorp Vault (for cloud-agnostic deployments)
  - AWS Secrets Manager (if deployed on AWS)
- **Integration credentials** (GitHub App private key, Azure DevOps PAT, Slack tokens) stored as Kubernetes Secrets, never in config files
- **API keys** (Claude API key) injected as environment variables, rotated via CI pipeline

---

## 4. Licensing Strategy

### Recommended: Apache License 2.0

**Why Apache 2.0 over MIT or AGPL:**

- **Enterprise-friendly** — legal teams at mid-size/enterprise companies (your target clients) are comfortable with Apache 2.0. MIT is also fine but lacks patent protection. AGPL scares enterprises away.
- **Patent grant** — Apache 2.0 includes an explicit patent license, which MIT does not. This matters for enterprise adoption.
- **Compatible with commercial offerings** — if you later offer Cartogra Cloud (hosted version) or Cartogra Enterprise (with SSO, audit log export, etc.), Apache 2.0 allows this without relicensing.
- **Community signal** — Apache 2.0 signals "serious open source project" more than MIT. Spring, Kafka, Kubernetes — all Apache 2.0.

### Future Commercial Layer (Optional)

If you decide to monetize beyond consulting:

- **Open core model:** The Apache 2.0 core handles everything in this scope document. Premium features (SSO/SAML, advanced RBAC, audit log export, SLA-backed support) live in a separate proprietary module.
- **Hosted offering:** Cartogra Cloud — managed instance, no setup required. The open-source version remains fully functional for self-hosting.
- **This is a decision for later.** For now, ship everything as Apache 2.0 and focus on the consulting pipeline.

---

## 5. Hosting & Cost Budget

### Live Demo (cartogra.dev) — Recommended Stack

| Resource | Spec | Monthly Cost (est.) |
|----------|------|---------------------|
| **Kubernetes cluster** | 3-node cluster (2 vCPU, 4GB each) on DigitalOcean DOKS or Hetzner | €45-75 |
| **PostgreSQL** | Managed, 2 vCPU, 4GB RAM, 50GB storage (DigitalOcean or Supabase) | €30-50 |
| **Kafka** | Upstash Kafka (serverless, pay-per-message) or Redpanda on a small VM | €15-30 |
| **Redis** | Managed, 1GB (DigitalOcean or Upstash) | €10-15 |
| **Domain** | cartogra.dev | €12/year |
| **SSL** | Let's Encrypt (free) | €0 |
| **Claude API** | Estimated 1000 queries/month demo usage, Sonnet for most, Opus for complex | €20-50 |
| **Monitoring** | Grafana Cloud free tier (10k metrics, 50GB logs) | €0 |
| **Total** | | **€130-230/month** |

### Cost Optimization for the Demo

- **Rate limit Claude API:** 10 queries per guest session, 50 per logged-in user per day
- **Cache frequent queries:** Redis cache for NL queries with TTL, so identical questions don't hit Claude again
- **Seed data is static:** The "Acme Fintech" demo org doesn't need real sync jobs running — it's pre-seeded
- **Scale to zero when possible:** Ingestion workers can be scaled down since the demo doesn't need real-time sync
- **Upstash Kafka:** Serverless pricing means you pay per message, not for idle brokers — ideal for a demo with bursty traffic

### Alternative: Minimal Demo Stack

If budget is tight, the entire demo can run on a single 4 vCPU / 8GB VM (Hetzner: ~€16/month) with Docker Compose. Less impressive from a "production-grade" standpoint, but functional and honest — you can note in the README that the production architecture uses Kubernetes.

---

## 6. Spring Boot Module Configuration

### Bootstrap & version policy

- **New services:** Generate the initial project from **[Spring Initializr](https://start.spring.io/)** (Gradle, Jar, same Java baseline as the repo, dependencies: Web, Actuator, Flyway, OpenTelemetry, etc.) and merge the output into `services/<name>/` so every module looks the same.
- **Versions:** Use the **current LTS Java** and the **latest stable Spring Boot 3.x** line compatible with the chosen Spring Cloud release train; bump the BOM in the root `build.gradle.kts` on a schedule (see scope §3 *Key Technical Decisions*).
- **Persistence:** Use **`spring-boot-starter-data-jdbc`** (not JPA) for all domain services — see project scope for the JDBC vs JPA rationale.

### Shared Dependency Versions (root `build.gradle.kts`)

```kotlin
plugins {
    // Pin to latest stable Boot 3.x / management plugin when you create the repo; bump deliberately.
    id("org.springframework.boot") version "3.3.0" apply false
    id("io.spring.dependency-management") version "1.1.5" apply false
    id("com.google.cloud.tools.jib") version "3.4.2" apply false
}

subprojects {
    group = "io.cartogra"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Shared dependency constraints
extra["springCloudVersion"] = "2023.0.2"
extra["testcontainersVersion"] = "1.19.8"
extra["flywayVersion"] = "10.15.0"
```

### Service Module Template (`services/registry/build.gradle.kts`)

```kotlin
plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
    kotlin("jvm")  // or java
}

dependencies {
    implementation(project(":shared:common"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Database
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Redis
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Observability
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Test
    testImplementation(project(":shared:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.springframework.kafka:spring-kafka-test")
}
```

### Shared Common Module (`shared/common/build.gradle.kts`)

```kotlin
// This is a library, NOT a Spring Boot application
plugins {
    id("java-library")
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
}
```

**Rule:** `shared:common` has zero Spring dependencies. It contains only plain Java: event envelopes, value objects, shared DTOs, exception classes. This keeps it lightweight and ensures domain logic doesn't leak framework dependencies.

### Versioning Strategy

- **Monorepo, single version:** All services share the same version number. When you tag `v0.5.0`, all 6 services are `v0.5.0`. This simplifies release management for a solo developer.
- **Semantic versioning:** `MAJOR.MINOR.PATCH`. While pre-1.0, breaking changes bump MINOR.
- **Container tags:** `cartogra/registry:0.5.0` and `cartogra/registry:latest`
- **API versioning:** URL path prefix `/api/v1`. When v2 is needed, the service serves both `/api/v1` and `/api/v2` simultaneously during migration. This is defined in ADR when it happens.
- **Flyway migrations:** Prefixed with `V001__`, `V002__`, etc. per service. Each service owns its own schema (logical isolation within the same PostgreSQL instance for simplicity, separate databases in production if needed).

---

## 7. Seed Data Specification — "Acme Fintech"

### Organization Profile

- **Tenant:** Acme Fintech
- **Industry:** Digital payments and banking infrastructure
- **SCM Setup:** 12 services on GitHub, 8 services on Azure DevOps (showing dual-provider support)
- **5 Teams:**

| Team | Slug | Focus | Contact |
|------|------|-------|---------|
| Platform Engineering | `platform` | Infrastructure, shared libraries, CI/CD | #team-platform |
| Payments | `payments` | Payment processing, checkout, settlement | #team-payments |
| Identity & Access | `identity` | Authentication, authorization, KYC | #team-identity |
| Risk & Compliance | `risk` | Fraud detection, AML, reporting | #team-risk |
| Customer Experience | `cx` | Customer portal, notifications, support tools | #team-cx |

### 20 Services

| # | Service | Team | SCM | Tech Stack | Tier | Health |
|---|---------|------|-----|------------|------|--------|
| 1 | `api-gateway` | platform | GitHub | Java, Spring Cloud Gateway | critical | HEALTHY |
| 2 | `auth-service` | identity | Azure DevOps | Java, Spring Security, Redis | critical | HEALTHY |
| 3 | `user-service` | identity | Azure DevOps | Java, Spring Boot, PostgreSQL | critical | HEALTHY |
| 4 | `kyc-service` | identity | Azure DevOps | Java, Spring Boot, PostgreSQL | standard | DEGRADED |
| 5 | `payment-processor` | payments | GitHub | Java, Spring Boot, Kafka | critical | HEALTHY |
| 6 | `checkout-api` | payments | GitHub | Java, Spring Boot | critical | HEALTHY |
| 7 | `settlement-engine` | payments | GitHub | Java, Spring Boot, Kafka | critical | HEALTHY |
| 8 | `refund-service` | payments | GitHub | Java, Spring Boot, Kafka | standard | HEALTHY |
| 9 | `fraud-detector` | risk | Azure DevOps | Java, Spring Boot, Redis | critical | HEALTHY |
| 10 | `aml-scanner` | risk | Azure DevOps | Java, Spring Boot | standard | HEALTHY |
| 11 | `compliance-reporter` | risk | Azure DevOps | Java, Spring Boot, PostgreSQL | standard | UNHEALTHY |
| 12 | `customer-portal-bff` | cx | GitHub | Java, Spring Boot | standard | HEALTHY |
| 13 | `notification-service` | cx | GitHub | Java, Spring Boot, Kafka | standard | HEALTHY |
| 14 | `support-ticket-api` | cx | GitHub | Java, Spring Boot, PostgreSQL | standard | HEALTHY |
| 15 | `event-bus` | platform | GitHub | Kafka Connect, Java | critical | HEALTHY |
| 16 | `config-service` | platform | GitHub | Java, Spring Cloud Config | critical | HEALTHY |
| 17 | `audit-log-service` | platform | Azure DevOps | Java, Spring Boot, Kafka | critical | HEALTHY |
| 18 | `legacy-payment-bridge` | payments | Azure DevOps | Java, Spring Boot | experimental | UNKNOWN |
| 19 | `internal-dashboard` | platform | GitHub | React, Node.js | standard | HEALTHY |
| 20 | `fee-calculator` | payments | GitHub | Java, Spring Boot | standard | HEALTHY |

### Pre-Baked Scenarios (Demo Talking Points)

**3 Orphaned Services (no owner):**
- `legacy-payment-bridge` — last commit 8 months ago, no team assigned, still receiving traffic (observed dependency from `payment-processor`). *Demo story: "The person who built this left 6 months ago. Nobody knows if it's safe to turn off."*
- `compliance-reporter` — marked UNHEALTHY, health checks failing for 2 weeks, owner team `risk` hasn't acknowledged. *Demo story: "Is anyone watching this?"*
- `internal-dashboard` — Platform team built it but never claimed ownership. It has 0 declared dependencies but observed traffic shows it calls 5 other services. *Demo story: "Shadow IT, but internal."*

**1 Breaking Contract Change Pending:**
- `payment-processor` published v3.2.0 of its Payment API, adding a required `idempotency_key` field to the `POST /payments` endpoint. This is a breaking change affecting `checkout-api`, `refund-service`, and `customer-portal-bff`. Status: PENDING approval. *Demo story: "This would have broken 3 services in production if we shipped without checking."*

**2 Circular Dependency Chains:**
- `auth-service` → `user-service` → `kyc-service` → `auth-service` (the KYC service calls auth to validate tokens, creating a cycle). *Demo story: "If auth goes down, user goes down, KYC goes down, which... needs auth."*
- `fraud-detector` → `payment-processor` → `fraud-detector` (payments calls fraud for risk scoring, fraud calls payments for transaction history). *Demo story: "Classic mutual dependency. Either one going down takes both out."*

**1 God Service:**
- `auth-service` — 12 other services depend on it (every service except `event-bus`, `config-service`, and itself). Fan-in: 12. *Demo story: "Your single point of failure. What's your plan when this goes down?"*

**Dependency Drift:**
- `customer-portal-bff` declares dependencies on `user-service` and `checkout-api`, but observed traffic (OTel traces) shows it also calls `payment-processor` directly and `notification-service`. Two undeclared dependencies. *Demo story: "The BFF is calling services it doesn't declare. The architecture diagram is a lie."*
- `fee-calculator` declares a dependency on `settlement-engine`, but zero observed traffic on that path in the last 30 days. Dead dependency. *Demo story: "Someone added this to the spec and never removed it. Or the feature was reverted. Nobody knows."*

### Seed Data Format

The seed data lives in `seed/seed-data.json` as a structured file that the `seed-loader` script consumes via Cartogra's own API. This is intentional — it validates that the APIs work end-to-end.

```json
{
  "tenant": { "name": "Acme Fintech", "slug": "acme-fintech" },
  "teams": [ ... ],
  "connections": [
    { "provider": "GITHUB", "name": "acme-corp", "config": { ... } },
    { "provider": "AZURE_DEVOPS", "name": "acme-azure", "config": { ... } }
  ],
  "services": [ ... ],
  "dependencies": [ ... ],
  "contracts": [ ... ],
  "contract_checks": [ ... ],
  "findings": [ ... ]
}
```

---

## 8. Security Hardening

### API Security

- **Rate limiting:** **Every** HTTP route is subject to rate limiting implemented as **gateway filters** (middleware-style), backed by Redis token buckets. Defaults can stay: 100 req/min per user, 1000 req/min per tenant; CI endpoints (`/ci/check`): 50 req/min per **API key** or IP bucket; **tighter limits on `/auth/*`** to slow abuse.
- **Input validation:** All request bodies validated via Jakarta Bean Validation (`@Valid`). Maximum request body size: 5MB (for spec uploads).
- **CORS:** Strict origin allowlist. Demo allows `cartogra.dev` only. Self-hosted instances configure their own origins.
- **Headers:** `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security`, `Content-Security-Policy`

### Demo Abuse Prevention

- **Guest mode is read-only.** No mutations without authentication.
- **Logged-in users get a sandbox tenant.** They cannot modify the "Acme Fintech" demo tenant.
- **Claude API rate limits per session:** 10 NL queries per guest session (cookie-based), 50 per authenticated user per day.
- **No real external integrations in demo.** GitHub/Azure DevOps connections in the demo tenant are simulated with pre-seeded data. Visitors cannot connect their own repos to the demo instance.
- **Auto-cleanup:** Sandbox tenants created by logged-in users are deleted after 7 days of inactivity.

### Credential Rotation

| Credential | Rotation Frequency | Mechanism |
|------------|-------------------|-----------|
| GitHub App private key | 90 days | Regenerate via GitHub → update K8s Secret → rolling restart |
| Azure DevOps PAT | 90 days | Regenerate via Azure DevOps → update K8s Secret |
| Azure DevOps Service Principal | 365 days (client secret) | Rotate via Azure AD → update K8s Secret |
| Claude API key | On compromise only | Regenerate via Anthropic Console → update K8s Secret |
| Slack webhook URLs | On compromise only | Regenerate via Slack App settings |
| JWT signing key | 180 days | Generate new key → deploy → old key remains valid for 24h for in-flight tokens |
| Database passwords | 90 days | Rotate via managed DB provider → update K8s Secret → rolling restart |

### Dependency Security

- **Dependabot** enabled for all modules (Gradle, npm)
- **Trivy** container scan in CI pipeline — blocks merge on CRITICAL/HIGH CVEs
- **OWASP Dependency-Check** Gradle plugin as an additional layer
- **No snapshot dependencies** in production builds

---

## 9. PR Template & Issue Templates

### Pull Request Template (`.github/PULL_REQUEST_TEMPLATE.md`)

```markdown
## What

Brief description of what this PR does.

## Why

What problem does this solve? Link to issue if applicable.

## How

Key implementation decisions. If this is architecturally significant,
link to the ADR.

## Checklist

- [ ] Tests added/updated
- [ ] API docs updated (if endpoints changed)
- [ ] Flyway migration added (if schema changed)
- [ ] ADR written (if architectural decision made)
- [ ] No new CRITICAL/HIGH vulnerabilities in Trivy scan
- [ ] Conventional commit message format

## Type

- [ ] Feature
- [ ] Bug fix
- [ ] Refactor
- [ ] Infrastructure
- [ ] Documentation
```

### Architecture Discussion Template (`.github/ISSUE_TEMPLATE/architecture_discussion.md`)

```markdown
---
name: Architecture Discussion
about: Propose or discuss a significant technical decision
labels: architecture, discussion
---

## Context

What problem or opportunity are we considering?

## Options

What approaches have you considered? Brief pros/cons for each.

## Questions for Discussion

What trade-offs are you unsure about?

## Related

Link to relevant code, docs, or external references.

<!-- If this leads to a decision, capture it as an ADR in docs/adr/ -->
```

---

## 10. Day-One Checklist

Everything you need to do before writing business logic:

```
Week 1, Day 1-2: Repository Setup
- [ ] Create GitHub repo: cartogra/cartogra
- [ ] Add LICENSE (Apache 2.0)
- [ ] Add README.md with project description, vision, and "under construction" badge
- [ ] Add CONTRIBUTING.md
- [ ] Add .gitignore, .editorconfig
- [ ] Add PR template and issue templates
- [ ] Create GitHub Project board with phases as milestones
- [ ] Set up branch protection on main (require PR, require CI pass)

Week 1, Day 2-3: Build System
- [ ] Initialize Gradle multi-module project (root + shared/common + services/registry stub)
- [ ] Configure Spring Boot, current LTS Java, dependency management (align versions with Spring Initializr / Spring Cloud BOM)
- [ ] Add OpenTelemetry + Micrometer Prometheus registry to the template; verify traces reach your collector
- [ ] Create shared:common module with EventEnvelope, TenantId, ServiceId
- [ ] Create shared:test-support module with Testcontainers helpers
- [ ] Verify: `./gradlew build` passes with empty service stubs

Week 1, Day 3-4: Local Infrastructure
- [ ] Create docker-compose.yml with PostgreSQL, Kafka (Redpanda), Redis
- [ ] Create docker-compose.dev.yml with debug ports and volume mounts
- [ ] Verify: `docker compose up` starts all infrastructure
- [ ] Create Flyway migration V001__create_tenants.sql
- [ ] Verify: service starts and applies migration

Week 1, Day 4-5: CI Pipeline
- [ ] Create .github/workflows/ci.yml
- [ ] Steps: checkout → setup Java 21 → Gradle build → test → Trivy scan
- [ ] Verify: push to PR triggers pipeline and passes
- [ ] Create Dockerfile for registry service (multi-stage build); **policy:** every deployable (each Java service, frontend, workers) has a Dockerfile
- [ ] Verify: Docker build succeeds in CI
- [ ] Add Prometheus scrape config (Compose or k8s) and a minimal Grafana dashboard for JVM/redis metrics

Week 2, Day 1-2: Frontend Skeleton
- [ ] Initialize React app with Vite, TypeScript, Tailwind, shadcn/ui
- [ ] Create basic layout: sidebar nav, header, empty page shells
- [ ] Add Dockerfile for frontend
- [ ] Add to docker-compose.yml
- [ ] Verify: frontend loads at localhost:3000

Week 2, Day 2-3: ADR Foundation
- [x] Commit docs/adr/TEMPLATE.md
- [x] Commit docs/adr/README.md
- [x] Write and commit ADR-0001 (PostgreSQL over graph database)
- [x] Write and commit ADR-0002 (SCM provider abstraction)
- [x] Add architecture reference docs: `docs/architecture/system-overview.md`, `docs/architecture/data-model.md`, `docs/architecture/kafka-topics.md`
- [x] Add API specs: `docs/api/gateway.openapi.yaml`, `docs/api/registry.openapi.yaml`, `docs/api/topology.openapi.yaml`, `docs/api/contract.openapi.yaml`, `docs/api/intelligence.openapi.yaml`
- [x] Add runbooks: `docs/runbooks/local-development.md`, `docs/runbooks/deployment.md`, `docs/runbooks/incident-response.md`

Week 2, Day 3-5: First Public Content
- [ ] Publish blog post: "Why your service catalog is always wrong"
- [ ] Publish blog post: "How I use Architecture Decision Records to think in public"
- [ ] Post Twitter/LinkedIn launch thread
- [ ] Record 5-min intro video (optional — can defer)

Done: You now have a buildable, testable, deployable skeleton with CI,
local dev infrastructure, and your first public content out the door.
Phase 1 (Registry Service) begins.
```

---

*This implementation guide is the companion to the Cartogra Project Scope Definition. Together, they provide everything needed to start building.*
