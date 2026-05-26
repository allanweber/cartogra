# Cartogra — Service Intelligence Platform

## Project Scope Definition & Build-in-Public Plan

---

## 1. Vision

**One-liner:** "As your microservice count grows past 20, nobody knows what exists, who owns what, what depends on what, whether contracts are being honored, or whether it's safe to deploy."

Cartogra is an open-source service intelligence platform that gives engineering organizations real-time visibility into their service ecosystem. It auto-discovers services, maps dependencies, guards API contracts, and uses AI to surface architectural risks — all without requiring engineers to maintain yet another wiki page.

### Why This Project Exists

Every mid-size and enterprise company running microservices hits the same wall: tribal knowledge becomes the only source of truth. When people leave, context disappears. When teams ship independently, contracts break silently. When architects try to reason about the system, they draw diagrams on whiteboards that are outdated by the time the marker dries.

Existing solutions either do too little (Backstage is a catalog, not intelligence) or cost too much (Cortex, Port — SaaS, enterprise pricing, vendor lock-in). There's a real gap for an open-source, opinionated, intelligent platform that treats service visibility as an engineering discipline, not an afterthought.

### Target Users

- **Engineering Managers & Directors** — "What do we actually have running, and who owns it?"
- **Staff/Principal Engineers** — "Is it safe to deprecate this API field? What's the blast radius?"
- **Platform Teams** — "How do we enforce API contract standards across 15 teams?"
- **CTOs & VPs of Engineering** — "Where are our architectural risks hiding?"

---

## 2. Core Pillars

### Pillar 1: Living Service Registry

A service catalog that stays accurate because it pulls from reality — not because someone remembered to update a Confluence page.

**Capabilities:**

- Auto-discovery from Kubernetes clusters (namespaces, deployments, services)
- Git repository scanning from GitHub **and Azure DevOps Repos** (detect services by repo structure, Dockerfiles, Helm charts)
- Ownership tracking via CODEOWNERS files (GitHub) and **required reviewers / team ownership metadata** (Azure DevOps), git history analysis, and manual assignment
- Health aggregation from Kubernetes probes and custom health endpoints
- Tech stack detection from build files (pom.xml, package.json, Dockerfile)
- Staleness detection — flag services with no deploys or commits in configurable timeframes
- Orphan detection — services running in production with no claimed owner
- Service profiles with documentation links, runbooks, SLA targets, and team contacts

**Key differentiator:** The registry is append-only with temporal versioning. You can ask: "What did the service landscape look like 3 months ago? What changed?"

### Pillar 2: Dependency Intelligence

Understands how services relate to each other — both how they say they connect (declared) and how they actually connect (observed).

**Capabilities:**

- Declared dependency mapping from OpenAPI specs, AsyncAPI specs, and build configuration
- Observed dependency mapping from OpenTelemetry trace data ingestion (opt-in)
- Drift detection — highlight differences between declared and observed dependencies
- Interactive dependency graph visualization (D3-based, explorable)
- Impact analysis — "If I take down Service X for 30 minutes, what breaks?"
- Blast radius calculation — for any given service, how many transitive dependents exist?
- Single point of failure detection — services with high fan-in and no redundancy
- Circular dependency detection and warnings

**Key differentiator:** Dual-mode (declared vs observed) dependency mapping with drift detection. Most tools only do one or the other.

### Pillar 3: Contract Guardian

Prevents the "Team A changed their API and Team B broke in production" scenario by making contract changes visible, validated, and communicated before they reach production.

**Capabilities:**

- Schema registry for OpenAPI and AsyncAPI specifications
- Automatic breaking change detection on schema updates (field removal, type changes, required field additions)
- Consumer-producer relationship tracking — which services consume which APIs
- Compatibility matrix dashboard — green/yellow/red per consumer-producer pair
- Schema diff visualization — side-by-side comparison with highlighted changes
- Notification pipeline — Slack/Microsoft Teams/webhook alerts to affected teams on breaking changes
- CI/CD integration — GitHub Action **and Azure DevOps Pipeline Task** that blocks merges with uncoordinated breaking changes
- Schema evolution timeline — full history of how each API has changed over time

**Key differentiator:** Consumer-driven contract testing made accessible without requiring consumer teams to write contract tests manually.

### Pillar 4: AI-Powered Insights

Transforms raw metadata into actionable intelligence using Claude API.

**Capabilities:**

- Natural language queries: "Show me all services owned by Team Payments that haven't been deployed in 90 days"
- Architectural anti-pattern detection: circular dependencies, god services (too many dependents), orphaned services, tight coupling clusters
- Change impact analysis: "I want to deprecate field X from my API — who consumes it?"
- Ownership suggestions for orphaned services based on git history and team patterns
- Anomaly detection in service behavior (sudden dependency changes, unusual deploy patterns)
- Architecture health score with drill-down explanations
- Weekly digest generation — automated summary of ecosystem changes, risks, and recommendations

**Key differentiator:** Not a chatbot bolted on. The AI layer answers questions that currently require 30+ minutes of manual investigation across multiple tools.

---

## 3. Architecture

### High-Level System Design

```
┌──────────────────────────────────────────────────────────────────┐
│                        React Frontend                           │
│  Service Catalog │ Dependency Graph │ Contract Hub │ Insights    │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                    ┌──────┴──────┐
                    │   Gateway   │  Auth · Rate limit · Trace · Routing
                    └──────┬──────┘
                           │
      ┌────────────────────┼──────────────────────┐
      │                    │                      │
┌─────┴──────┐   ┌────────┴────────┐   ┌─────────┴──────────┐
│  Registry  │   │    Topology     │   │     Contract       │
│  Service   │   │    Service      │   │     Service        │
│            │   │                 │   │                    │
│ CRUD       │   │ Graph building  │   │ Schema store       │
│ Auto-sync  │   │ Impact analysis │   │ Compat checks      │
│ Ownership  │   │ Drift detect    │   │ Break detection    │
│ Health     │   │ SPOF analysis   │   │ Notifications      │
└─────┬──────┘   └────────┬────────┘   └─────────┬──────────┘
      │                   │                      │
      └─────────┬─────────┴──────────┬───────────┘
                │                    │
         ┌──────┴──────┐    ┌───────┴────────┐
         │    Kafka     │    │  Intelligence  │
         │              │    │    Service     │
         │ Registry     │    │               │
         │  events      │    │ NL queries    │
         │ Schema       │    │ Anti-patterns │
         │  changes     │    │ Suggestions   │
         │ Sync         │    │ Digest gen    │
         │  triggers    │    └───────────────┘
         └──────────────┘
                │
      ┌─────────┴──────────┐
      │  Ingestion Workers  │
      │                     │
      │ GitHub sync         │
      │ Azure DevOps sync   │
      │ K8s sync            │
      │ OTel collector      │
      └─────────────────────┘
```

### Service Breakdown

| Service | Responsibility | Key Patterns |
|---------|---------------|--------------|
| **Gateway Service** | **MVP:** login flows, cookie + Bearer token issuance/validation, authentication (local + OTP, social SSO, tenant OIDC), multi-tenant routing, **rate limiting on all routes**, OpenTelemetry trace propagation | Spring Cloud Gateway, Spring Security, Redis-backed limits, W3C trace context |
| **Registry Service** | Service CRUD, auto-sync from Kubernetes, GitHub, and Azure DevOps, ownership tracking, health aggregation, temporal versioning | CQRS (write model vs read projections), event sourcing for audit trail, scheduled sync jobs |
| **Topology Service** | Dependency graph construction and querying, impact analysis, drift detection between declared and observed dependencies | Graph algorithms (BFS/DFS for blast radius), event-driven graph updates via Kafka, adjacency list with recursive CTEs |
| **Contract Service** | API schema storage, compatibility validation, breaking change detection, consumer notification | Schema evolution detection (OpenAPI diff), outbox pattern for reliable event publishing, consumer-driven contracts |
| **Intelligence Service** | Natural language queries via Claude API, anti-pattern detection, change impact analysis, weekly digest generation | AI integration with structured prompts, batch analysis jobs, complex domain queries |
| **Ingestion Workers** | Async workers pulling from GitHub API, Azure DevOps API, Kubernetes API, OpenTelemetry collectors | Kafka consumers, idempotent processing, circuit breakers on external APIs, backoff/retry |

### Communication Patterns

- **Synchronous (REST):** Queries and commands requiring immediate response (service lookup, contract validation, graph queries)
- **Asynchronous (Apache Kafka):** Event sourcing, ingestion triggers, cross-service notifications, audit trail
- **CQRS:** Registry writes to an event store; reads are projected into optimized query models (PostgreSQL for relational queries, Redis for hot lookups)
- **Outbox Pattern:** Contract Service writes events to an outbox table in the same transaction as the schema update, then a publisher relay pushes to Kafka — eliminating dual-write inconsistency
- **Saga Pattern:** Multi-step workflows (e.g., "new service discovered → enrich from GitHub/Azure DevOps → scan for API specs → register contracts → update topology") with compensating actions on failure

### Key Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Message broker | Apache Kafka | Durable, ordered, replayable event log; fits event sourcing naturally; widely adopted in enterprise |
| Primary database | PostgreSQL | JSONB for flexible metadata, recursive CTEs for graph traversal, proven reliability |
| Caching | Redis | Hot path lookups (service health, dependency graph snapshots), session/rate-limit state |
| Graph storage | PostgreSQL (not Neo4j) | Operational simplicity for the scale we're targeting (<5000 services); recursive CTEs handle traversal; one less database to operate |
| API schemas | OpenAPI 3.x + AsyncAPI 2.x | Industry standards; broad tooling support; machine-parseable for compatibility checks |
| Search | PostgreSQL full-text search (initially) | Avoids Elasticsearch operational overhead early; upgradeable later if needed |
| AI integration | Claude API (Anthropic) | Best reasoning capability for complex architectural queries; structured output support |
| SCM abstraction | Provider SPI (GitHub + Azure DevOps) | Many enterprises use Azure DevOps; abstraction layer avoids vendor lock-in and proves extensible design |
| Java / Spring baseline | Current **LTS Java** + latest stable **Spring Boot 3.x** (Spring Cloud aligned) | Security patches, long support window, ecosystem compatibility; new service apps bootstrapped consistently |
| Service bootstrap | [Spring Initializr](https://start.spring.io/) (or start.spring.io metadata in IDE) | Same dependencies, packaging, and conventions across gateway, domain services, and workers |
| Persistence access | **Spring Data JDBC** (not JPA) | See *Spring Data JDBC vs JPA* below |
| Transactional email | **Resend** | Reliable product email for OTP, invites, and notifications without operating an SMTP stack |
| Container delivery | **Docker** for every deployable (Java services, frontend, workers) | One artifact shape from laptop to CI to Kubernetes; Compose for local parity |
| API edge protection | **Rate limiting** on all HTTP APIs | Redis-backed limits at the gateway; defense-in-depth optional per-service limits for sensitive routes |

### Spring Data JDBC vs JPA (decision)

| | Spring Data JDBC | Spring Data JPA |
|--|------------------|-----------------|
| Fit for Cartogra | **Strong** — the data model is migration-driven (Flyway), uses advanced SQL (recursive CTEs, temporal queries), and favors explicit aggregates and projections | Weaker default — graph and history queries fight ORM mapping; lazy loading and session boundaries add operational risk in Kafka workers and long requests |
| Complexity | You write SQL for hard parts (`JdbcTemplate` / `@Query` with native SQL); simple CRUD stays small | Faster for trivial CRUD; non-trivial reporting often reverts to native queries anyway |
| Performance & predictability | No lazy loads; memory and query count are obvious | Risk of N+1 and accidental fetches unless carefully tuned |

**Choice for Cartogra:** **Spring Data JDBC** as the default stack for all backend services, plus `JdbcTemplate` (or small repository methods) where the scope document already assumes hand-authored SQL. Revisit only if a service is overwhelmingly simple CRUD with no graph/temporal SQL — even then, JDBC keeps the monorepo consistent.

### Traceability & HTTP API contract

- **OpenTelemetry (Java)** is enabled in **every** JVM service (gateway, domain services, ingestion workers). Traces export via OTLP (collector or hosted backend). Metrics continue through Micrometer → **Prometheus**; dashboards and alerting in **Grafana**. Logs are structured (JSON) and include the same trace identifiers for correlation (Grafana Loki or existing ELK stack is an implementation detail — the requirement is *correlatable* logs, not a specific vendor).
- **Trace context propagation:** Incoming HTTP requests accept W3C **`traceparent`** (and honor **`tracestate`** when present). The gateway and each service propagate context to downstream HTTP calls and Kafka producers so a single logical request produces one connected trace.
- **Response envelope (Cartogra Java / Spring REST only):** Responses from **Cartogra’s own Spring Boot HTTP APIs** (gateway and domain services) use one of:
  - Success: `{ "data": <T>, "traceId": "<string>" }`
  - Error: `{ "error": <standard error object>, "traceId": "<string>" }`
  **Inbound webhooks** (GitHub, Azure DevOps, Slack, Teams, etc.) keep the **provider’s JSON shape**; Cartogra still records trace context in logs and OTel for handling, but must not wrap provider callbacks in this envelope. The **React frontend** consumes the Spring APIs and therefore sees the envelope. Kafka uses the separate **event envelope** (see §6).
  The `traceId` is the **OpenTelemetry trace id** for the request: exactly **32 lowercase hexadecimal characters** (128 bits), same value as in the active trace’s root/span context and as embedded in `traceparent` when present. No shortened or alternate display form — one canonical id end-to-end (logs, metrics exemplars, Grafana, support tickets).
- **Standard error object:** Same shape for all 4xx/5xx responses on those Spring APIs, e.g. `{ "code": "<stable machine code>", "message": "<human-readable>", "details": { } }` (field names canonical in OpenAPI). Validation errors map into `details`.
- **Trace header:** Clients SHOULD send W3C **`traceparent`**; responses SHOULD include **`X-Trace-Id`** set to that same **32-hex** trace id (no `00-` prefix — unlike the middle segment of `traceparent`, which is `version-trace_id-span_id-flags`).

### Identity & session security

- **Registration / login (local):** Email + password; **email OTP** (via Resend) confirms ownership before the account is active.
- **SSO (user):** **Google** (Sign in with Google — any Google account by default) and **GitHub** OAuth2 sign-in alongside local credentials.
- **Company / tenant SSO:** Organizations can sign in via **OAuth2/OIDC** (e.g. Azure Entra ID, Okta, or generic OIDC) for workforce access — distinct from per-user social SSO where both are needed.
- **Session and API tokens (MVP):** **Browser clients** use an **httpOnly cookie** (session or JWT) issued after login. **Non-browser clients** (scripts, CLIs, integrations calling Cartogra REST) use **`Authorization: Bearer <access token>`** for the same access. Both paths are supported; the gateway validates either credential. **MVP:** issuance and validation for Cartogra users live in the **gateway** (no separate auth microservice until post-MVP).
- **CI / automation (`POST /ci/check` and similar):** Callers MUST present a **tenant-scoped API key** only (v1: no HMAC). Use a dedicated header such as **`X-Cartogra-Api-Key`** (exact name fixed in OpenAPI). Rate limits still apply. Rotate keys from the Cartogra UI or tenant admin API; GitHub Actions / Azure Pipelines store the secret in the platform’s secret store.
- **Rate limiting:** All API routes go through a **central rate-limit layer** (Spring Cloud Gateway filters / Redis token bucket). Per-tenant and per-user limits apply; stricter limits on auth and expensive operations.

---

## 4. Real Integrations

| Integration | Purpose | Implementation |
|-------------|---------|----------------|
| **GitHub API** | Repo scanning for services, CODEOWNERS parsing, OpenAPI/AsyncAPI spec discovery, commit/deploy history | GitHub App with webhook listeners + scheduled polling |
| **Azure DevOps API** | Repo scanning, team/project ownership mapping, pipeline run history, pull request policies, spec discovery | Azure DevOps PAT or Service Principal auth, REST API + Service Hooks for webhooks |
| **Kubernetes API** | Live service discovery, health status, namespace mapping, resource metadata | K8s Java client with watch streams for real-time updates |
| **Slack API** | Breaking change notifications, orphan alerts, weekly digest delivery, interactive approval workflows | Slack App with incoming webhooks + interactive messages |
| **Microsoft Teams** | Same notification capabilities as Slack for Azure-centric organizations | Teams incoming webhooks + Adaptive Cards |
| **OpenTelemetry Collector** | Trace data ingestion for observed dependency mapping | OTLP gRPC receiver, span analysis for service-to-service call extraction |
| **Claude API (Anthropic)** | Natural language queries, anti-pattern analysis, impact summaries, ownership suggestions | Anthropic Java SDK with structured prompts and response parsing |
| **Resend** | Transactional email — registration/login OTP, security notices, digest delivery where email is used | HTTP API from backend services; API key in secrets |
| **GitHub Actions** | CI integration — contract compatibility check as a PR status check | Custom GitHub Action published to marketplace |
| **Azure Pipelines** | CI integration — contract compatibility check as a pipeline task | Custom Azure DevOps Pipeline Extension published to marketplace |

### SCM Provider Abstraction (SPI Design)

Because organizations use different source control platforms, Cartogra uses a **Service Provider Interface** to abstract SCM operations. This is an intentional architectural decision that proves extensible design thinking.

```
ScmProvider (interface)
├── discoverRepositories(orgConfig) → List<RepoMetadata>
├── scanForSpecs(repo, patterns) → List<ApiSpecFile>
├── getOwnership(repo) → OwnershipInfo
├── getCommitHistory(repo, since) → List<CommitInfo>
├── getPipelineRuns(repo, since) → List<PipelineRun>
├── registerWebhook(repo, events, callbackUrl) → WebhookRegistration
└── parseWebhookPayload(headers, body) → ScmEvent

GitHubProvider implements ScmProvider
├── Uses GitHub App authentication (JWT + installation tokens)
├── CODEOWNERS file parsing for ownership
├── GitHub Actions workflow runs for deploy history
└── Webhook events: push, pull_request, workflow_run

AzureDevOpsProvider implements ScmProvider
├── Uses PAT or Service Principal (OAuth2) authentication
├── Team/Area path mapping for ownership
├── Azure Pipelines run history for deploy tracking
└── Service Hooks for webhook events: git.push, git.pullrequest.updated, build.complete
```

**Why this matters for your portfolio:** It shows you think about abstraction boundaries and extensibility without over-engineering. The SPI pattern is exactly what enterprise architects look for — it's how real platform teams build things that last.

---

## 5. Frontend (TanStack Start)

### Views

**Service Catalog**
- Card-based layout with health indicators, ownership badges, staleness warnings
- Search and filter by team, tech stack, health, namespace, **SCM provider (GitHub / Azure DevOps)**
- Quick-drill into any service: dependencies, contracts, recent changes, health history
- Orphan services highlighted with suggested owners
- SCM provider icon on each card (GitHub octocat / Azure DevOps logo) for quick identification

**Dependency Graph**
- Interactive D3-based force-directed graph
- Click any node to see upstream/downstream dependencies
- Toggle between "declared" and "observed" modes
- Drift overlay: highlight where declared ≠ observed
- "What-if" mode: select a service, see blast radius highlighted
- Zoom, pan, cluster by team/namespace

**Contract Hub**
- Schema diff viewer (side-by-side with highlighted changes)
- Compatibility matrix: producer rows × consumer columns, green/yellow/red
- Timeline view: how each API has evolved over time
- Breaking change queue: pending changes awaiting consumer acknowledgment

**Intelligence Panel**
- Chat interface for natural language queries over the service graph
- Anti-pattern feed: severity-ranked findings with remediation guidance
- Architecture health score dashboard with trend over time
- Change impact simulator: input a proposed change, see predicted effects

**Operations View**
- Real-time event stream: syncs, schema changes, health transitions, deploys
- Ingestion pipeline health: are all sources connected and syncing?
- Platform metrics: services tracked, contracts validated, queries served

### Frontend Tech Stack

- TanStack Start with React + TypeScript
- Routing: TanStack Router (file-based routes)
- State management: Zustand (lightweight, no boilerplate)
- Data fetching/caching: TanStack Query
- Tables/data grid: TanStack Table
- Forms: TanStack Forms
- Visualization: D3.js for dependency graph, Recharts for dashboards
- UI components: shadcn/ui + Tailwind CSS
- Testing: Vitest + React Testing Library + Playwright (E2E)
- Docs: TanStack Start https://tanstack.com/start/latest · Query https://tanstack.com/query/latest · Router https://tanstack.com/router/latest · Table https://tanstack.com/table/latest · Forms https://tanstack.com/forms/latest

---

## 6. Kafka Topic Structure

### Design Principles

- **Topic-per-entity-event-type** — fine-grained topics enable independent consumer scaling and selective subscription
- **Keying strategy** — all events are keyed by the primary entity ID (service_id, contract_id, etc.) to guarantee ordering per entity within a partition
- **Retention** — domain events retained for 30 days (replayable for rebuilding projections); audit events retained for 1 year
- **Schema** — all messages use JSON with a versioned envelope schema; Avro considered for Phase 2 if throughput demands it
- **Idempotency** — every event carries a deterministic `event_id` (UUIDv5 from entity_id + event_type + timestamp) for consumer-side deduplication
- **Partitioning** — 12 partitions default for domain topics (sufficient for <5000 services); 6 for lower-volume topics

### Event Envelope Schema

Every message on every topic follows this envelope:

```json
{
  "event_id": "uuid-v5-deterministic",
  "event_type": "service.registered",
  "entity_id": "uuid-of-the-entity",
  "tenant_id": "uuid-of-the-tenant",
  "timestamp": "2026-01-15T10:30:00Z",
  "version": 1,
  "correlation_id": "uuid-from-request-or-saga",
  "caused_by": "event-id-that-triggered-this | null",
  "source": "registry-service",
  "payload": { }
}
```

### Topic Catalog

> Naming convention is `cartogra.{domain}.{entity}.{event}` — dots only, no hyphens. This matches the implemented producers and consumers; the execution checklist is the canonical source for topic names if this table drifts.

#### Registry Domain — `cartogra.registry.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.registry.service.registered` / `.updated` / `.deleted` | service_id | Registry Service | Topology, Intelligence, Notification | Core lifecycle events |
| `cartogra.registry.scm-connection.created` / `.updated` / `.deleted` | connection_id | Registry Service | Ingestion (connection cache) | SCM connection lifecycle |
| `cartogra.registry.sync.command` | connection_id | Webhook Controller, Scheduler, Admin UI, K8s Worker | Ingestion Workers | Trigger a sync run |

#### Ingestion Domain — `cartogra.ingestion.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.ingestion.sync.completed` | sync_job_id | Ingestion Workers | Registry Service | Sync result with status (`COMPLETED`, `FAILED`, `PARTIAL`) |
| `cartogra.ingestion.ownership.resolved` | repo_id | Ingestion Workers | Registry Service | Per-repo CODEOWNERS resolution |
| `cartogra.ingestion.service.discovered` | external_id | Ingestion Workers (SCM + K8s) | Registry Service | Auto-discovered services with tech stack + repo/k8s metadata |
| `cartogra.ingestion.spec.discovered` | content_sha256 | Ingestion Workers | Contract Service | OpenAPI / AsyncAPI specs found in repos |

> Webhook controller publishes `cartogra.registry.sync.command` directly — there is no separate `webhook-events` topic. Raw webhook signature verification happens at the controller; only successfully verified, relevant events become sync commands.

#### Topology Domain — `cartogra.topology.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.topology.dependency.declared` / `.observed` / `.removed` / `.drift-detected` | source_service_id | Topology Service | Intelligence, Notification | Dependency lifecycle |
| `cartogra.topology.analysis.completed` | analysis_id | Topology Service | Intelligence, Frontend | Impact analysis / SPOF / cycle findings |

#### Observability — `cartogra.observability.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.observability.spans` | trace_id | OTel Collector | Topology Service (`OtelSpanWorker`) | Pre-processed spans used to derive observed dependencies |

#### Contract Domain — `cartogra.contract.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.contract.schema.published` / `.updated` / `.deprecated` | contract_id | Outbox Relay | Topology, Intelligence, Notification | Schema lifecycle (sourced from outbox table) |
| `cartogra.contract.check.passed` / `.breaking` / `.approved` / `.blocked` | check_id | Outbox Relay | Notification, CI extensions | Compatibility check results |

#### Intelligence Domain — `cartogra.intelligence.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.intelligence.analysis.requested` | tenant_id | Scheduled Jobs, Admin UI | Intelligence Service | Trigger anti-pattern scan / digest / health-score / ownership-suggestion / anomaly-detection runs |
| `cartogra.intelligence.analysis.completed` | tenant_id | Intelligence Service | Notification, Frontend | Run results |

#### Platform / Cross-cutting — `cartogra.platform.*`

| Topic | Key | Producers | Consumers | Purpose |
|-------|-----|-----------|-----------|---------|
| `cartogra.platform.audit.recorded` | entity_id | All non-registry services | Registry Service (`audit_events` writer) | Cross-service audit events centralized in the registry table |
| `cartogra.platform.notification.queued` | recipient_team_id | Notification Service | Delivery Workers (Slack, Teams, Email, Webhook) | Outbound notification fan-out |
| `cartogra.platform.dead.letter` | original_topic | All Consumers (on failure) | Ops Dashboard, DLQ replay admin API | Failed messages after retry exhaustion |

### Consumer Groups

| Consumer Group | Subscribes To | Purpose |
|----------------|---------------|---------|
| `topology-graph-builder` | `cartogra.registry.service.*`, `cartogra.contract.schema.*`, `cartogra.observability.spans` | Rebuilds dependency graph on relevant changes |
| `intelligence-analyzer` | `cartogra.registry.service.*`, `cartogra.topology.dependency.*`, `cartogra.contract.schema.*` | Feeds AI analysis pipeline |
| `notification-router` | `cartogra.contract.check.*`, `cartogra.topology.dependency.*`, `cartogra.intelligence.analysis.completed`, `cartogra.registry.service.*` | Routes events to notification fan-out |
| `notification-delivery-slack` | `cartogra.platform.notification.queued` (filtered) | Delivers to Slack |
| `notification-delivery-teams` | `cartogra.platform.notification.queued` (filtered) | Delivers to Microsoft Teams |
| `notification-delivery-email` | `cartogra.platform.notification.queued` (filtered) | Delivers via Resend |
| `audit-writer` (in Registry Service) | `cartogra.platform.audit.recorded` | Persists cross-service audit events into `audit_events` |
| `registry-service-discovery` | `cartogra.ingestion.service.discovered` | Upserts services discovered by SCM / K8s workers |
| `registry-ownership-consumer` | `cartogra.ingestion.ownership.resolved` | Assigns teams to services via CODEOWNERS |
| `ingestion-sync` | `cartogra.registry.sync.command` | Dispatches sync runs to GitHub / Azure DevOps / Kubernetes providers |
| `ingestion-connection-cache` | `cartogra.registry.scm-connection.*` | Maintains the local SCM connection cache used by scheduler + webhooks |
| `contract-spec-processor` | `cartogra.ingestion.spec.discovered` | Parses discovered specs, registers/updates contracts |
| `outbox-relay` | (polls DB `outbox_events` table, not Kafka) | Publishes outbox rows to `cartogra.contract.schema.*` and `cartogra.contract.check.*` |

### Dead Letter Queue Strategy

When a consumer fails to process a message after 3 retries (exponential backoff: 1s, 5s, 25s):

1. Original message published to `cartogra.platform.dead.letter` with metadata: original topic, partition, offset, consumer group, error, stack trace, retry count
2. Alert fired to Grafana ops dashboard
3. DLQ messages replayable via admin API endpoint after root cause is fixed

---

## 7. Database Schema (Detailed)

### Design Principles

- **Multi-tenant by default** — every table includes `tenant_id` with row-level security policies
- **Temporal versioning** — key entities maintain a history table for point-in-time queries
- **Soft deletes** — `deleted_at` column instead of hard deletes; audit trail is never lost
- **JSONB for flexible metadata** — avoids schema migrations for every new field; indexed with GIN for queries
- **UUIDs as primary keys** — no sequential IDs exposed; safe for distributed generation
- **Timestamps always UTC** — `TIMESTAMPTZ` everywhere

### Enum Types

```sql
CREATE TYPE scm_provider AS ENUM ('GITHUB', 'AZURE_DEVOPS');
CREATE TYPE health_status AS ENUM ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN');
CREATE TYPE dependency_type AS ENUM ('DECLARED', 'OBSERVED');
CREATE TYPE dependency_protocol AS ENUM ('REST', 'GRPC', 'KAFKA', 'GRAPHQL', 'UNKNOWN');
CREATE TYPE spec_type AS ENUM ('OPENAPI_3', 'ASYNCAPI_2');
CREATE TYPE compat_status AS ENUM ('COMPATIBLE', 'BREAKING', 'UNKNOWN');
CREATE TYPE check_status AS ENUM ('PENDING', 'APPROVED', 'BLOCKED', 'AUTO_APPROVED');
CREATE TYPE change_severity AS ENUM ('INFO', 'WARNING', 'BREAKING');
CREATE TYPE pattern_severity AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');
CREATE TYPE notification_channel AS ENUM ('SLACK', 'TEAMS', 'WEBHOOK', 'EMAIL');
CREATE TYPE sync_status AS ENUM ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'PARTIAL');
```

### Core Tables

#### Tenant & Organization

```sql
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    settings        JSONB NOT NULL DEFAULT '{}',
    -- settings: { "staleness_threshold_days": 90, "auto_approve_non_breaking": true,
    --             "notification_channels": ["SLACK"], "ai_features_enabled": true }
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE teams (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    contact_slack   VARCHAR(255),
    contact_teams   VARCHAR(255),
    contact_email   VARCHAR(255),
    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, slug)
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255) NOT NULL,
    role            VARCHAR(50) NOT NULL DEFAULT 'member',  -- 'admin', 'member', 'viewer'
    team_id         UUID REFERENCES teams(id),
    auth_provider   VARCHAR(50) NOT NULL,    -- 'local', 'google', 'github', 'azure_ad', 'oidc', ...
    auth_subject    VARCHAR(255) NOT NULL,   -- external identity ID or stable local subject key
    password_hash   VARCHAR(255),             -- NULL when SSO-only; bcrypt/argon2 for local
    email_verified_at TIMESTAMPTZ,            -- NULL until OTP (Resend) confirms email
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, email)
);
```

#### SCM Connections

```sql
CREATE TABLE scm_connections (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    provider        scm_provider NOT NULL,
    name            VARCHAR(255) NOT NULL,
    config          JSONB NOT NULL,
    -- GitHub:      { "installation_id": 12345678, "org_name": "acme-corp", "app_id": 98765 }
    -- Azure DevOps: { "organization": "acme-corp", "project": "platform-services",
    --                 "auth_type": "service_principal", "tenant_id_azure": "azure-ad-uuid" }
    sync_enabled    BOOLEAN NOT NULL DEFAULT true,
    last_sync_at    TIMESTAMPTZ,
    last_sync_status sync_status,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, provider, name)
);

CREATE TABLE scm_webhooks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id   UUID NOT NULL REFERENCES scm_connections(id),
    external_id     VARCHAR(255) NOT NULL,
    callback_url    VARCHAR(512) NOT NULL,
    events          TEXT[] NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### Service Registry

```sql
CREATE TABLE services (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255) NOT NULL,
    description     TEXT,

    -- Source control
    scm_connection_id UUID REFERENCES scm_connections(id),
    repository_url  VARCHAR(512),
    repository_ref  VARCHAR(255),        -- default branch
    repo_external_id VARCHAR(255),       -- repo ID from provider

    -- Kubernetes
    k8s_cluster     VARCHAR(255),
    k8s_namespace   VARCHAR(255),
    k8s_deployment  VARCHAR(255),

    -- Ownership
    owner_team_id   UUID REFERENCES teams(id),

    -- Detected metadata
    tech_stack      TEXT[] NOT NULL DEFAULT '{}',
    tags            TEXT[] NOT NULL DEFAULT '{}',
    tier            VARCHAR(20),         -- 'critical', 'standard', 'experimental'

    -- Health
    health_status   health_status NOT NULL DEFAULT 'UNKNOWN',
    health_checked_at TIMESTAMPTZ,
    health_endpoint VARCHAR(512),

    -- Activity
    last_deploy_at  TIMESTAMPTZ,
    last_commit_at  TIMESTAMPTZ,
    last_commit_sha VARCHAR(40),

    -- Documentation
    documentation_url VARCHAR(512),
    runbook_url     VARCHAR(512),
    sla_target      VARCHAR(50),

    -- Flexible metadata
    metadata        JSONB NOT NULL DEFAULT '{}',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_services_tenant ON services(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_owner ON services(owner_team_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_health ON services(tenant_id, health_status) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_tech_stack ON services USING GIN(tech_stack) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_tags ON services USING GIN(tags) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_metadata ON services USING GIN(metadata) WHERE deleted_at IS NULL;
CREATE INDEX idx_services_fulltext ON services USING GIN(
    to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, ''))
) WHERE deleted_at IS NULL;

-- Temporal versioning: snapshot of service state at each change
CREATE TABLE services_history (
    history_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id      UUID NOT NULL REFERENCES services(id),
    tenant_id       UUID NOT NULL,
    changed_by      UUID REFERENCES users(id),
    change_type     VARCHAR(20) NOT NULL,    -- 'CREATED', 'UPDATED', 'DELETED'
    changed_fields  TEXT[],
    snapshot        JSONB NOT NULL,           -- full service state at this point
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_services_history_lookup ON services_history(service_id, recorded_at DESC);
CREATE INDEX idx_services_history_tenant ON services_history(tenant_id, recorded_at DESC);
```

#### Dependencies (Topology)

```sql
CREATE TABLE dependencies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    source_service_id   UUID NOT NULL REFERENCES services(id),
    target_service_id   UUID NOT NULL REFERENCES services(id),
    dependency_type     dependency_type NOT NULL,
    protocol            dependency_protocol NOT NULL DEFAULT 'UNKNOWN',

    confidence_score    DECIMAL(3,2),         -- 0.00 to 1.00 (observed deps)
    observation_count   INTEGER DEFAULT 0,

    description         TEXT,
    target_endpoint     VARCHAR(512),
    metadata            JSONB NOT NULL DEFAULT '{}',

    first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,

    UNIQUE (tenant_id, source_service_id, target_service_id, dependency_type, protocol),
    CHECK (source_service_id != target_service_id)
);

CREATE INDEX idx_deps_source ON dependencies(source_service_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_deps_target ON dependencies(target_service_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_deps_tenant_type ON dependencies(tenant_id, dependency_type) WHERE deleted_at IS NULL;

-- Materialized view for fast graph queries (refreshed on dependency changes)
CREATE MATERIALIZED VIEW dependency_graph AS
SELECT
    d.tenant_id, d.source_service_id, ss.name AS source_name,
    d.target_service_id, ts.name AS target_name,
    d.dependency_type, d.protocol, d.confidence_score
FROM dependencies d
JOIN services ss ON d.source_service_id = ss.id
JOIN services ts ON d.target_service_id = ts.id
WHERE d.deleted_at IS NULL AND ss.deleted_at IS NULL AND ts.deleted_at IS NULL;

CREATE INDEX idx_depgraph_tenant ON dependency_graph(tenant_id);
CREATE INDEX idx_depgraph_source ON dependency_graph(source_service_id);
CREATE INDEX idx_depgraph_target ON dependency_graph(target_service_id);

-- Drift: where declared ≠ observed
CREATE TABLE dependency_drifts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    source_service_id   UUID NOT NULL REFERENCES services(id),
    target_service_id   UUID NOT NULL REFERENCES services(id),
    drift_type          VARCHAR(50) NOT NULL,
    -- 'OBSERVED_NOT_DECLARED', 'DECLARED_NOT_OBSERVED', 'PROTOCOL_MISMATCH'
    declared_dep_id     UUID REFERENCES dependencies(id),
    observed_dep_id     UUID REFERENCES dependencies(id),
    resolved            BOOLEAN NOT NULL DEFAULT false,
    resolved_at         TIMESTAMPTZ,
    resolved_by         UUID REFERENCES users(id),
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata            JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_drifts_tenant ON dependency_drifts(tenant_id, resolved) WHERE resolved = false;
```

#### API Contracts

```sql
CREATE TABLE api_contracts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    service_id      UUID NOT NULL REFERENCES services(id),  -- producer
    name            VARCHAR(255) NOT NULL,
    spec_type       spec_type NOT NULL,
    current_version VARCHAR(50) NOT NULL,

    spec_content    JSONB NOT NULL,
    spec_hash       VARCHAR(64) NOT NULL,

    compat_status   compat_status NOT NULL DEFAULT 'UNKNOWN',
    deprecated      BOOLEAN NOT NULL DEFAULT false,
    deprecated_at   TIMESTAMPTZ,
    sunset_date     DATE,

    spec_file_path  VARCHAR(512),
    discovered_via  VARCHAR(50),    -- 'github_scan', 'azuredevops_scan', 'manual_upload'

    metadata        JSONB NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, service_id, name)
);

CREATE INDEX idx_contracts_service ON api_contracts(service_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_contracts_tenant ON api_contracts(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_contracts_status ON api_contracts(tenant_id, compat_status) WHERE deleted_at IS NULL;

CREATE TABLE contract_consumers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id         UUID NOT NULL REFERENCES api_contracts(id),
    consumer_service_id UUID NOT NULL REFERENCES services(id),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    consumed_version    VARCHAR(50),
    evidence_type       VARCHAR(50) NOT NULL,  -- 'declared', 'observed', 'manual'
    evidence_detail     TEXT,
    verified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    UNIQUE (contract_id, consumer_service_id)
);

CREATE INDEX idx_consumers_contract ON contract_consumers(contract_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_consumers_service ON contract_consumers(consumer_service_id) WHERE deleted_at IS NULL;

CREATE TABLE contract_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id     UUID NOT NULL REFERENCES api_contracts(id),
    tenant_id       UUID NOT NULL,
    version         VARCHAR(50) NOT NULL,
    spec_content    JSONB NOT NULL,
    spec_hash       VARCHAR(64) NOT NULL,
    published_by    UUID REFERENCES users(id),
    commit_sha      VARCHAR(40),
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (contract_id, version)
);

CREATE INDEX idx_contract_versions ON contract_versions(contract_id, published_at DESC);

CREATE TABLE contract_checks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    contract_id         UUID NOT NULL REFERENCES api_contracts(id),
    previous_version_id UUID REFERENCES contract_versions(id),
    new_version_id      UUID NOT NULL REFERENCES contract_versions(id),

    status              check_status NOT NULL DEFAULT 'PENDING',
    is_breaking         BOOLEAN NOT NULL,

    changes             JSONB NOT NULL,
    -- [{"path": "/paths/~1payments/post/requestBody", "type": "field_added_required",
    --   "severity": "BREAKING", "description": "Required field 'idempotency_key' added"}]

    affected_consumers  UUID[] NOT NULL DEFAULT '{}',

    resolved_by         UUID REFERENCES users(id),
    resolved_at         TIMESTAMPTZ,
    resolution_note     TEXT,

    ci_provider         VARCHAR(50),     -- 'github_actions', 'azure_pipelines'
    ci_run_url          VARCHAR(512),
    pr_url              VARCHAR(512),

    checked_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_checks_contract ON contract_checks(contract_id, checked_at DESC);
CREATE INDEX idx_checks_status ON contract_checks(tenant_id, status) WHERE status = 'PENDING';
```

#### Intelligence & Analysis

```sql
CREATE TABLE analysis_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    analysis_type   VARCHAR(50) NOT NULL,
    -- 'anti_pattern_scan', 'health_score', 'digest', 'impact_analysis'
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    input_params    JSONB NOT NULL DEFAULT '{}',
    results         JSONB,
    summary         TEXT,
    health_score    DECIMAL(5,2),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    ai_tokens_used  INTEGER,
    triggered_by    UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_tenant ON analysis_runs(tenant_id, created_at DESC);
CREATE INDEX idx_analysis_type ON analysis_runs(tenant_id, analysis_type, created_at DESC);

CREATE TABLE anti_pattern_findings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    analysis_run_id UUID NOT NULL REFERENCES analysis_runs(id),
    pattern_type    VARCHAR(100) NOT NULL,
    -- 'circular_dependency', 'god_service', 'orphaned_service', 'tight_coupling_cluster',
    -- 'missing_owner', 'stale_service', 'undocumented_dependency', 'single_point_of_failure'
    severity        pattern_severity NOT NULL,
    affected_services UUID[] NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     TEXT NOT NULL,
    remediation     TEXT,
    evidence        JSONB NOT NULL,
    -- circular_dependency: {"cycle": ["svc-a","svc-b","svc-c","svc-a"], "protocols": ["REST","KAFKA","REST"]}
    acknowledged    BOOLEAN NOT NULL DEFAULT false,
    acknowledged_by UUID REFERENCES users(id),
    acknowledged_at TIMESTAMPTZ,
    resolved        BOOLEAN NOT NULL DEFAULT false,
    resolved_at     TIMESTAMPTZ,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_findings_tenant ON anti_pattern_findings(tenant_id, resolved, severity);
CREATE INDEX idx_findings_run ON anti_pattern_findings(analysis_run_id);
CREATE INDEX idx_findings_services ON anti_pattern_findings USING GIN(affected_services);

CREATE TABLE nl_query_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    query_text      TEXT NOT NULL,
    generated_sql   TEXT,
    results_summary TEXT,
    response_time_ms INTEGER,
    ai_tokens_used  INTEGER,
    feedback        VARCHAR(20),     -- 'helpful', 'not_helpful', null
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_nl_queries_tenant ON nl_query_log(tenant_id, created_at DESC);
```

#### Sync & Ingestion

```sql
CREATE TABLE sync_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    connection_id   UUID NOT NULL REFERENCES scm_connections(id),
    status          sync_status NOT NULL DEFAULT 'PENDING',
    job_type        VARCHAR(50) NOT NULL,   -- 'full_scan', 'incremental', 'webhook_triggered'
    services_discovered INTEGER DEFAULT 0,
    services_updated    INTEGER DEFAULT 0,
    specs_found         INTEGER DEFAULT 0,
    errors              JSONB NOT NULL DEFAULT '[]',
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sync_jobs_connection ON sync_jobs(connection_id, created_at DESC);
CREATE INDEX idx_sync_jobs_status ON sync_jobs(tenant_id, status);
```

#### Notifications

```sql
CREATE TABLE notification_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    team_id         UUID REFERENCES teams(id),
    event_type      VARCHAR(100) NOT NULL,
    channel         notification_channel NOT NULL,
    channel_config  JSONB NOT NULL,
    -- Slack: {"webhook_url": "...", "channel": "#platform-alerts"}
    -- Teams: {"webhook_url": "..."}
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notification_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    rule_id         UUID REFERENCES notification_rules(id),
    channel         notification_channel NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    subject         VARCHAR(255),
    body_preview    TEXT,
    payload         JSONB NOT NULL,
    delivered       BOOLEAN NOT NULL DEFAULT false,
    delivered_at    TIMESTAMPTZ,
    error_message   TEXT,
    retry_count     INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_tenant ON notification_log(tenant_id, created_at DESC);
CREATE INDEX idx_notifications_delivery ON notification_log(delivered, retry_count)
    WHERE delivered = false;
```

#### Outbox (Transactional Event Publishing)

```sql
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    key             VARCHAR(255) NOT NULL,
    payload         JSONB NOT NULL,
    published       BOOLEAN NOT NULL DEFAULT false,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_unpublished ON outbox_events(created_at) WHERE published = false;
```

### Key SQL Patterns

#### Blast Radius (Recursive CTE)

```sql
WITH RECURSIVE blast_radius AS (
    SELECT d.source_service_id AS affected_id, 1 AS depth,
           ARRAY[d.target_service_id, d.source_service_id] AS path
    FROM dependencies d
    WHERE d.target_service_id = :target_service_id
      AND d.tenant_id = :tenant_id AND d.deleted_at IS NULL

    UNION ALL

    SELECT d.source_service_id, br.depth + 1, br.path || d.source_service_id
    FROM dependencies d
    JOIN blast_radius br ON d.target_service_id = br.affected_id
    WHERE d.tenant_id = :tenant_id AND d.deleted_at IS NULL
      AND d.source_service_id != ALL(br.path)
      AND br.depth < 10
)
SELECT DISTINCT s.id, s.name, s.owner_team_id, t.name AS team_name,
       MIN(br.depth) AS min_distance
FROM blast_radius br
JOIN services s ON br.affected_id = s.id
LEFT JOIN teams t ON s.owner_team_id = t.id
GROUP BY s.id, s.name, s.owner_team_id, t.name
ORDER BY min_distance, s.name;
```

#### Point-in-Time Service State

```sql
SELECT snapshot FROM services_history
WHERE service_id = :service_id AND recorded_at <= :point_in_time
ORDER BY recorded_at DESC LIMIT 1;
```

#### Circular Dependency Detection

```sql
WITH RECURSIVE dep_path AS (
    SELECT source_service_id, target_service_id,
           ARRAY[source_service_id] AS path, false AS is_cycle
    FROM dependencies
    WHERE tenant_id = :tenant_id AND deleted_at IS NULL

    UNION ALL

    SELECT dp.source_service_id, d.target_service_id,
           dp.path || d.source_service_id,
           d.target_service_id = dp.source_service_id
    FROM dep_path dp
    JOIN dependencies d ON dp.target_service_id = d.source_service_id
    WHERE d.tenant_id = :tenant_id AND d.deleted_at IS NULL
      AND (NOT d.target_service_id = ANY(dp.path) OR d.target_service_id = dp.source_service_id)
      AND array_length(dp.path, 1) < 10
)
SELECT DISTINCT path || target_service_id AS cycle
FROM dep_path WHERE is_cycle = true;
```

### Database Migrations Strategy

- **Flyway** for versioned migrations (`V001__create_tenants.sql`, `V002__create_services.sql`, etc.)
- Each migration is idempotent and backward-compatible
- Separate `R__` (repeatable) migrations for materialized views and functions
- Migration tests run in CI via Testcontainers (spin up real PostgreSQL, apply migrations, validate)

---

## 8. Infrastructure & DevOps

### Local Development

- Docker Compose with all services, Kafka, PostgreSQL, Redis, and mock external services
- Hot-reload for all Java services (Spring DevTools) and React frontend
- Seed data scripts that create a realistic 20-service organization with dependencies, contracts, and some intentional problems (orphans, breaking changes, circular deps)

### CI/CD Pipeline (GitHub Actions + Azure Pipelines)

Cartogra's own CI runs on GitHub Actions. It also ships an **Azure DevOps Pipeline Extension** so orgs using Azure Pipelines can integrate contract checks.

```
Push to PR:
  → Lint (Checkstyle, ESLint)
  → Unit Tests (JUnit 5, Vitest)
  → Integration Tests (Testcontainers)
  → Contract Tests (schema compat validation)
  → Container Build (multi-stage Dockerfile)
  → Security Scan (Trivy)
  → Deploy to Staging (Kubernetes)
  → Smoke Tests
  → PR Status Report

Merge to main:
  → All above
  → Deploy to Production
  → Post-deploy health check
  → Notify Slack / Teams
```

### Kubernetes Deployment

- Helm charts per service with configurable replicas, resource limits, HPA
- Health checks: liveness, readiness, and startup probes
- ConfigMaps and Secrets for environment-specific configuration
- Network policies for service-to-service communication control
- Ingress with TLS termination

### Observability

- **Distributed Tracing:** **OpenTelemetry** Java agent/SDK in **all** JVM services; export OTLP to the collector → **Grafana Tempo**. The standard is OTLP + trace IDs in logs and API envelopes.
- **Metrics:** Micrometer → **Prometheus** (scrape) → **Grafana** dashboards (RED/USE, JVM, Kafka lag, business KPIs)
- **Logging:** Structured JSON with **traceId** / span fields matching OTel; ship to **Grafana Loki**, ELK, or cloud logging — goal is query-by-trace and Grafana correlation
- **Business Metrics:** Custom dashboards for services tracked, contracts validated, AI query patterns
- **Alerting:** Grafana alerting for platform health (ingestion lag, sync failures, API latency)

### Infrastructure as Code

- Terraform modules for cloud resources (managed Kubernetes, databases, Kafka, Redis)
- Environment parity: staging mirrors production topology
- Feature flags via Unleash (open source) for progressive rollouts

---

## 9. Quality & Engineering Practices

- **Architecture Decision Records (ADRs):** Every significant technical choice documented with context, decision, and consequences (see Appendix A for template)
- **API-first development:** OpenAPI specs written before implementation
- **Testing pyramid:** Unit (70%) → Integration with Testcontainers (20%) → E2E with Playwright (10%)
- **Code review standards:** PR template with checklist (tests, ADR if architectural, schema migration if DB change)
- **Conventional commits:** Standardized commit messages for changelog generation
- **Dependency management:** Dependabot + automated security patching
- **Performance benchmarks:** Load tests with k6 for critical paths (graph traversal, contract validation)

---

## 10. Build-in-Public Plan

### Philosophy

Building in public isn't just about sharing progress — it's about demonstrating how a senior/staff engineer thinks. Every post should reveal decision-making, not just output. The audience is CTOs, engineering managers, and fellow senior engineers who might hire you.

### Platform Strategy

| Platform | Purpose | Cadence |
|----------|---------|---------|
| **GitHub** | Source of truth — code, ADRs, issues, project board, discussions | Continuous |
| **Personal Blog / Dev.to** | Long-form technical deep dives | Biweekly |
| **Twitter/X or LinkedIn** | Short-form updates, architecture sketches, decision threads | 3-4x per week |
| **YouTube** | Architecture walkthrough videos, coding sessions | Monthly |

### Content Calendar — Phase by Phase

#### Phase 0: Foundation (Weeks 1-2) — "Setting the Stage"

**Goal:** Establish the project publicly, explain the problem, get early feedback.

- [ ] Blog post: "Why your service catalog is always wrong — and what I'm building about it"
- [ ] Blog post: "How I use Architecture Decision Records to think in public — and why you should too" — introduce the ADR template, explain why documenting decisions matters more than documenting code
- [ ] GitHub repo initialized with README, ADR template, architecture diagram, and project board
- [ ] Twitter/LinkedIn thread: "I'm building an open-source service intelligence platform. Here's the problem I'm solving and why nothing else does it well."
- [ ] Twitter thread: "Before I write a single line of code, I'm designing my database schema and Kafka topic structure in the open. Here's why I start with the data model, not the API." — share the entity relationship sketch
- [ ] Record 5-min video: "The problem with microservice visibility" — use whiteboard-style diagrams

**What to ship:** Project skeleton, Docker Compose with empty service stubs, CI pipeline that builds and tests (even if tests are minimal), seed data design document, ADR template committed, initial database migration scripts (tenants, teams, users, scm_connections).

#### Phase 1: Registry Service (Weeks 3-6) — "The Backbone"

**Goal:** Build the core service catalog with Kubernetes auto-discovery and dual SCM support.

- [ ] ADR-0001: "Why PostgreSQL over a graph database for our service registry"
- [ ] ADR-0002: "SCM provider abstraction — GitHub and Azure DevOps behind a common SPI"
- [ ] Blog post: "Building a self-healing service registry that doesn't rely on humans"
- [ ] Blog post: "Designing a multi-tenant schema with temporal versioning — why I chose soft deletes, JSONB metadata, and snapshot history tables" — walk through the services + services_history table design, explain the trade-off between full snapshots vs field-level diffs
- [ ] Twitter thread: Walk through the CQRS implementation — show the event store vs read model
- [ ] Twitter thread: "Most portfolio projects only support GitHub. Real enterprises use Azure DevOps. Here's how I built a provider abstraction (SPI) so Cartogra supports both without duplicating code." — show the interface + two implementations side by side
- [ ] Twitter thread: "I designed my database to answer questions about the past. Here's what temporal versioning looks like in PostgreSQL" — share the services_history table and the point-in-time query
- [ ] LinkedIn post: "Why I support Azure DevOps and not just GitHub — a lesson in understanding your customer" — short, business-focused angle on the SPI decision
- [ ] GitHub: Ship with full test suite, API docs, Docker Compose integration
- [ ] Short posts: Screenshots of the registry UI, interesting bugs encountered, design trade-offs

**What to ship:** Working Registry Service with CRUD API, K8s auto-discovery, GitHub + Azure DevOps repo scanner, basic React catalog view, Flyway migrations for all registry tables.

#### Phase 2: Topology Service (Weeks 7-10) — "The Map"

**Goal:** Dependency graph with declared + observed mapping.

- [ ] ADR-0003: "Graph traversal with recursive CTEs vs dedicated graph database"
- [ ] ADR-0006: "Kafka topic structure and event envelope design"
- [ ] Blog post: "Mapping microservice dependencies: declared vs observed, and why you need both"
- [ ] Blog post: "Designing a Kafka topic structure for a real distributed system — topic-per-entity, idempotent envelopes, and dead letter queues" — walk through the 16 topics, explain why topic-per-entity beats a monolithic event bus, show the envelope schema and DLQ strategy
- [ ] Blog post: "Recursive CTEs in PostgreSQL: how I built blast radius and circular dependency detection without a graph database" — share the actual SQL with explanations, benchmark results, and when you'd outgrow this approach
- [ ] Video: Live coding session — building the impact analysis algorithm
- [ ] Twitter thread: "I just built a 'what breaks if this goes down' feature. Here's how blast radius calculation works in practice." — include the recursive CTE SQL
- [ ] Twitter thread: "Materialized views are underrated. Here's how I use one to make dependency graph queries instant instead of joining 3 tables every time."
- [ ] Twitter thread: "I designed 16 Kafka topics for Cartogra. Here's the naming convention, keying strategy, and the one mistake most people make with event-driven systems." — focus on the idempotency key design (UUIDv5)
- [ ] LinkedIn post: "Event-driven architecture sounds great in theory. Here's what it actually looks like when you design the topic structure for a real system." — share the topic catalog diagram

**What to ship:** Topology Service with graph building, D3 dependency visualization, impact analysis endpoint, OTel ingestion pipeline, Kafka infrastructure with all topology and registry topics, materialized view for dependency_graph.

#### Phase 3: Contract Guardian (Weeks 11-15) — "The Shield"

**Goal:** API contract management with breaking change detection.

- [ ] ADR-0004: "Outbox pattern for reliable contract change notifications"
- [ ] Blog post: "Consumer-driven contract testing without the ceremony"
- [ ] Blog post: "How we detect breaking API changes automatically — the algorithm"
- [ ] Blog post: "The outbox pattern in practice — how I eliminated dual-write bugs between PostgreSQL and Kafka" — walk through the outbox_events table, the relay polling loop, the published index, and why this beats publishing directly from application code
- [ ] Blog post: "Publishing a GitHub Action AND an Azure DevOps Pipeline Extension for the same feature — how to reach both ecosystems" — practical guide to building CI integrations for both platforms
- [ ] GitHub Action published to marketplace: `cartogra/contract-check`
- [ ] Azure DevOps Extension published: `cartogra-contract-check`
- [ ] Twitter thread: Demo of the schema diff UI and breaking change workflow
- [ ] Twitter thread: "The contract_checks table stores structured diffs as JSONB — every breaking change is machine-readable, not just a boolean. Here's why that matters for automation."
- [ ] Twitter thread: "I built the same CI check for GitHub Actions AND Azure Pipelines. One codebase, two ecosystems. Here's the abstraction that made it clean."
- [ ] LinkedIn post: "Your API contract changed. Do you know which 7 services just broke? This is what Contract Guardian does." — demo GIF of the compatibility matrix

**What to ship:** Contract Service, schema registry, compatibility checker, Slack + Teams notifications, GitHub Action + Azure Pipelines Task, contract hub UI, outbox relay implementation, contract_versions history table with full evolution tracking.

#### Phase 4: Intelligence Layer (Weeks 16-19) — "The Brain"

**Goal:** AI-powered queries and architectural analysis.

- [ ] ADR-0005: "Integrating Claude API for architectural intelligence — prompt engineering decisions"
- [ ] Blog post: "Using LLMs for infrastructure intelligence — what works and what doesn't"
- [ ] Blog post: "I built a natural language query layer over PostgreSQL — here's how the nl_query_log table helps me improve prompts with real usage data" — explain the feedback loop: query → generated SQL → results → user feedback → prompt refinement
- [ ] Video: Demo of natural language queries over the service graph
- [ ] Twitter thread: "I asked Claude to analyze our dependency graph for anti-patterns. Here's what it found."
- [ ] Twitter thread: "I track every AI API call — tokens used, response time, user feedback. Here's the schema I use to keep LLM costs under control and measure whether the AI layer is actually useful."
- [ ] Twitter thread: "The anti_pattern_findings table stores AI-generated remediation advice alongside hard evidence (the actual cycle path, the fan-in count). Here's why mixing AI output with deterministic data builds trust."

**What to ship:** Intelligence Service, NL query interface, anti-pattern detection, weekly digest generator, architecture health score, nl_query_log for prompt improvement pipeline, analysis_runs with token tracking.

#### Phase 5: Polish & Production (Weeks 20-24) — "The Product"

**Goal:** Production-grade deployment, comprehensive docs, live demo.

- [ ] Blog post: "From side project to production: the observability stack that makes it real"
- [ ] Blog post: "20+ tables, 16 Kafka topics, 2 SCM providers — a full retrospective on the data architecture of Cartogra" — a capstone post that walks through the entire data layer end-to-end, what worked, what was over-engineered, what was under-designed
- [ ] Blog post: "Flyway migrations in a multi-service system — how I kept 20+ tables evolving safely across 6 services" — practical migration strategy, testing with Testcontainers, backward compatibility rules
- [ ] Video: 15-minute architecture walkthrough — narrated as if onboarding a staff engineer
- [ ] Full documentation site (Docusaurus or similar)
- [ ] Live demo at cartogra.dev with seeded realistic data
- [ ] Twitter thread: "I just shipped a complete service intelligence platform. Here's everything I'd do differently."
- [ ] Twitter thread: "Our dead letter queue caught 3 bugs in the first week of production. Here's what failed and how the DLQ replay API saved us." — real war story from running the system
- [ ] LinkedIn post: "6 months, 6 ADRs, and every decision documented publicly. Here's the index and why I'd recommend this approach to any senior engineer building in public."

**What to ship:** Helm charts, Terraform modules, Grafana dashboards, comprehensive README, live demo environment, documentation site, complete Flyway migration suite, DLQ replay admin endpoint.

### Build-in-Public Content Principles

1. **Show the thinking, not just the doing.** "I chose X over Y because..." is 10x more valuable than "I shipped X."
2. **Share failures and pivots.** "This approach didn't work, here's why I changed direction" demonstrates senior judgment.
3. **Make it skimmable.** Architecture diagrams, code snippets, and screenshots travel better than walls of text.
4. **Engage with feedback.** When someone suggests an alternative approach, explore it publicly. It shows intellectual humility.
5. **Link everything to the real problem.** Every technical post should start with the user pain it addresses.

---

## 11. Presentation Strategy

### Live Demo (cartogra.dev)

- Seeded with a realistic fake organization: "Acme Fintech"
- 20 services across 5 teams with realistic names, dependencies, and problems
- Services sourced from **both GitHub and Azure DevOps** to show dual-provider support
- Pre-baked scenarios visitors can explore:
  - 3 orphaned services with no owner
  - 1 breaking contract change pending review
  - 2 circular dependency chains
  - 1 god service with 12 dependents
  - Drift between declared and observed dependencies
- Guest mode — no signup required, read-only access to the demo org

### GitHub Repository

- Monorepo with clear module boundaries
- Comprehensive README with architecture diagram, quick start, and contributing guide
- `/docs/adr/` directory with all architecture decision records
- `/docs/architecture/` with system diagrams (generated and maintained)
- GitHub Project board showing the roadmap
- Issue templates for bugs, features, and architectural discussions
- Well-structured PR history with descriptive titles and bodies

### Technical Blog Posts (Core Series)

1. "Why your service catalog is always wrong"
2. "How I use Architecture Decision Records to think in public"
3. "Supporting GitHub and Azure DevOps without building two systems"
4. "Designing a multi-tenant schema with temporal versioning"
5. "Designing a Kafka topic structure for a real distributed system"
6. "Recursive CTEs in PostgreSQL: blast radius and circular dependency detection without a graph database"
7. "Consumer-driven contracts without the ceremony"
8. "The outbox pattern in practice — eliminating dual-write bugs between PostgreSQL and Kafka"
9. "Publishing a GitHub Action AND an Azure DevOps Pipeline Extension for the same feature"
10. "Using LLMs for infrastructure intelligence — what works and what doesn't"
11. "Building a natural language query layer over PostgreSQL with a feedback loop"
12. "20+ tables, 16 Kafka topics, 2 SCM providers — a full data architecture retrospective"

### Architecture Walkthrough Video (15 min)

- Narrated as if onboarding a new staff engineer
- Cover: problem space, high-level architecture, key technical decisions, event flows, data model, deployment topology
- Show the running system, trace a request through services, demonstrate the observability stack

---

## 12. Success Metrics

### Project Health

- All services running with >95% test coverage on critical paths
- CI/CD pipeline green with <10min build time
- Live demo available with <2s page load
- Documentation covers all services, APIs, and architectural decisions

### Build-in-Public Engagement

- GitHub stars as a signal (target: engagement over vanity)
- Blog post reads and shares
- Meaningful discussions in GitHub Issues/Discussions
- Conference talk proposals accepted (target 1-2)

### Consulting Pipeline

- Inbound inquiries from the project (track referral source)
- Interview requests citing Cartogra as the deciding factor
- Consulting engagements where Cartogra is deployed or adapted

---

## 13. Technology Summary

| Layer | Technology |
|-------|-----------|
| **Backend** | Current LTS Java, Spring Boot 3.x (bootstrapped via Spring Initializr), Spring Cloud Gateway, **Spring Data JDBC** |
| **Messaging** | Apache Kafka |
| **Database** | PostgreSQL 16, Redis |
| **Frontend** | React 18+, TypeScript, D3.js, Recharts, Tailwind CSS, shadcn/ui |
| **AI** | Claude API (Anthropic) |
| **Email** | Resend (OTP, transactional) |
| **Integrations** | GitHub API, Azure DevOps API, Kubernetes API, Slack API, Microsoft Teams, OpenTelemetry |
| **Containers** | Docker (all apps and services), Kubernetes, Helm |
| **CI/CD** | GitHub Actions, Azure Pipelines (extension) |
| **Observability** | OpenTelemetry (all services), Prometheus, Grafana Tempo (traces), Grafana Loki (logs), Grafana (dashboards + correlation) |
| **IaC** | Terraform |
| **Testing** | JUnit 5, Testcontainers, Vitest, Playwright, k6 |
| **Feature Flags** | Unleash |
| **Documentation** | Docusaurus |

---

## 14. Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| Scope creep — trying to build everything at once | High | Strict phase gates; each phase ships a usable increment |
| Kubernetes integration complexity for demo | Medium | Start with Docker Compose; K8s is a deployment target, not a dependency |
| OpenTelemetry ingestion volume in demo | Low | Synthetic trace generation for demo; real OTel is opt-in |
| AI costs for Claude API in live demo | Medium | Rate limiting, caching frequent queries, budget alerts |
| Competing with Backstage mindshare | Medium | Position as "intelligence, not just catalog" — different value prop |
| Azure DevOps API rate limits and auth complexity | Medium | Aggressive caching, incremental sync, clear setup docs |
| Burnout from build-in-public pressure | High | Batch content creation; reuse code artifacts as content; it's okay to go quiet |

---

## Appendix A: Architecture Decision Record (ADR) Template

Save as `docs/adr/TEMPLATE.md`. Each ADR gets its own file: `docs/adr/ADR-0001-postgresql-over-graph-database.md`.

```markdown
# ADR-XXXX: [Title of Decision]

## Status

[PROPOSED | ACCEPTED | DEPRECATED | SUPERSEDED by ADR-XXXX]

## Date

YYYY-MM-DD

## Context

What is the technical or business problem we're facing? What forces are at play?

Be specific. Include:
- The feature or component this affects
- Scale/performance requirements
- Team constraints (skills, timeline, operational burden)
- What we've tried or considered before

## Decision Drivers

- [Driver 1: e.g., "Operational simplicity — single database to manage"]
- [Driver 2: e.g., "Query patterns are primarily hierarchical, not arbitrary graph traversal"]
- [Driver 3: e.g., "Team has deep PostgreSQL experience, no Neo4j expertise"]

## Options Considered

### Option A: [Name]

**Description:** Brief explanation of this approach.

**Pros:**
- [Pro 1]
- [Pro 2]

**Cons:**
- [Con 1]
- [Con 2]

**Estimated effort:** [Low / Medium / High]

### Option B: [Name]

**Description:** Brief explanation of this approach.

**Pros:**
- [Pro 1]
- [Pro 2]

**Cons:**
- [Con 1]
- [Con 2]

**Estimated effort:** [Low / Medium / High]

### Option C: [Name] (if applicable)

...

## Decision

We chose **Option [X]** because [1-2 sentence summary of the primary reasoning].

## Consequences

### Positive

- [What becomes easier or better]
- [What risks are reduced]

### Negative

- [What becomes harder or worse]
- [What new risks are introduced]

### Neutral

- [Side effects neither clearly positive nor negative]

## Revisit Triggers

Under what conditions should we revisit this decision?

- [e.g., "If service count exceeds 5,000 and recursive CTE performance degrades below 200ms p99"]
- [e.g., "If we need multi-hop traversals deeper than 10 levels regularly"]

## References

- [Link to relevant documentation, benchmarks, or discussions]
- [Link to the GitHub Issue or PR where this was discussed]
```

### ADR Numbering & Filing Convention

- Sequential numbering: `ADR-0001`, `ADR-0002`, etc.
- Filename format: `ADR-XXXX-short-kebab-title.md`
- Index file: `docs/adr/README.md` maintains a table of all ADRs with status and one-line summary
- ADRs are never deleted — only marked as DEPRECATED or SUPERSEDED with a link to the replacement
- Every PR that introduces or changes an ADR links to it in the PR description

### Example ADR Index (`docs/adr/README.md`)

```markdown
# Architecture Decision Records

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-0001](ADR-0001-postgresql-over-graph-database.md) | PostgreSQL over graph database for service registry | ACCEPTED | 2026-XX-XX |
| [ADR-0002](ADR-0002-scm-provider-abstraction.md) | SCM provider abstraction — GitHub and Azure DevOps behind a common SPI | ACCEPTED | 2026-XX-XX |
| [ADR-0003](ADR-0003-recursive-ctes-for-graph-traversal.md) | Graph traversal with recursive CTEs vs dedicated graph database | ACCEPTED | 2026-XX-XX |
| [ADR-0004](ADR-0004-outbox-pattern-for-contract-events.md) | Outbox pattern for reliable contract change notifications | ACCEPTED | 2026-XX-XX |
| [ADR-0005](ADR-0005-claude-api-for-intelligence.md) | Integrating Claude API for architectural intelligence | ACCEPTED | 2026-XX-XX |
| [ADR-0006](ADR-0006-kafka-topic-design.md) | Kafka topic structure and event envelope design | ACCEPTED | 2026-XX-XX |
```

---

*This document is the living scope definition for Cartogra. It will evolve as decisions are made and shared publicly throughout the build process.*
