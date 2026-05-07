# Cartogra — Claude Rules

> Rules are split into focused files loaded below. Read this file fully before writing any code.

## Identity

- **Product**: Cartogra — living service registry + dependency intelligence platform
- **Monorepo root**: `cartogra/`
- **Style**: Multi-tenant SaaS, hexagonal architecture per service, event-driven via Kafka

| Service | Port | Role |
|---------|------|------|
| `gateway` | 8080 | Auth, routing, tenant injection, rate limiting |
| `registry` | 8081 | Service CRUD, team ownership, API contracts |
| `topology` | 8082 | Dependency graph, blast radius, cycle detection |
| `contract` | 8083 | Contract validation, breaking-change detection |
| `intelligence` | 8084 | Claude API integration, NL queries, health score |
| `ingestion` | 8085 | SCM/K8s sync workers, webhook receivers, spec discovery |
| `frontend` | 3000 | TanStack Start web app |

## Tech Stack (Non-Negotiable)

| Layer | Choice | NEVER use |
|-------|--------|-----------|
| Persistence | `spring-boot-starter-data-jdbc` + PostgreSQL | JPA / Hibernate / `EntityManager` |
| Migrations | Flyway (per-service) | Liquibase, `ddl-auto` |
| Messaging | Apache Kafka | RabbitMQ, SQS |
| Internal sync RPC | gRPC (`spring-grpc` 1.x, protobuf) | REST between services, Feign clients, `RestClient` for internal calls |
| Cache / Rate-limit | Redis | Memcached |
| Graph queries | Hand-written SQL + recursive CTEs | Graph DB, Neo4j |
| Tracing | OpenTelemetry (OTLP) | Zipkin client, Sleuth |
| Auth tokens | JWT (gateway-issued) | Per-service token issuance |
| Email | Resend | SendGrid, SES directly |
| AI | Cheapest Claude model | N/A |
| Frontend framework | TanStack Start | Next.js, Remix, custom SSR |
| Frontend routing | TanStack Router | React Router, reach/router |
| Frontend state | Zustand | Redux, Context for global state |
| Frontend data | TanStack Query | SWR, Apollo, raw fetch |
| Frontend table | TanStack Table | AG Grid, MUI Data Grid |
| Frontend forms | TanStack Forms | Formik, Redux Form |
| Frontend UI | shadcn/ui + Tailwind | MUI, Ant Design, Bootstrap |
| Frontend graphs | D3 | Cytoscape, vis.js |
| Frontend charts | Recharts | Chart.js |

## Critical Rules

**DO:**

- Add `tenant_id UUID NOT NULL` to EVERY new domain table
- Wrap ALL Spring REST responses in the envelope (except webhook receivers)
- Propagate OTel `traceparent` to ALL downstream HTTP calls, gRPC calls, and Kafka messages
- Use gRPC for ALL direct service-to-service synchronous calls — `.proto` files live in `shared:contracts`
- Use constructor injection in ALL Spring beans
- Set resource requests AND limits on EVERY K8s container
- Use `for_each` (not `count`) in Terraform for removable resources
- Write a Flyway migration for every schema change

**NEVER:**

- Add JPA / Hibernate to any service
- Add Spring dependencies to `shared:common` (plain Java only)
- Return `null` from a method — use `Optional<T>` or throw
- Hard-delete rows — use `deleted_at TIMESTAMPTZ` soft delete
- Use `@Autowired` field injection
- Use image tag `latest` in Dockerfiles or K8s manifests
- Hardcode credentials anywhere
- Expose actuator `*` in production
- Concatenate SQL strings — always use named params
- Run `terraform destroy` in CI without a human approval gate
- Use REST/HTTP for direct service-to-service calls — use gRPC
- Define `.proto` files inside a service module — they belong in `shared:contracts`

## File Structure

```
cartogra/
├── AGENTS.md                              # Full AI agent reference (human + other agents)
├── CLAUDE.md                              # This file — Claude Code auto-load
├── .claude/
│   ├── commands/                          # Slash commands for heavy scaffolding
│   └── rules/                             # Domain rule files (imported below)
├── services/
│   └── {name}/
│       ├── src/main/java/io/cartogra/{name}/
│       │   ├── api/                       # Controllers, request/response DTOs, mappers
│       │   ├── domain/                    # Entities, value objects, domain events
│       │   ├── application/               # Use cases, service interfaces
│       │   ├── infrastructure/            # JDBC repos, Kafka producers/consumers, HTTP clients
│       │   └── config/                    # Spring beans, security config, OTel config
│       └── src/main/resources/
│           └── db/migration/              # V001__init.sql, V002__add_field.sql ...
├── shared/
│   ├── common/                            # Plain Java only — ZERO Spring dependencies
│   └── test-support/                      # Testcontainers helpers (Postgres, Kafka)
├── frontend/                              # TanStack Start app
├── ci-extensions/
│   ├── github-action/                     # cartogra/contract-check GitHub Action
│   └── azure-pipelines-task/              # Azure DevOps extension
├── seed/                                  # Acme Fintech seed data + loader
├── perf/                                  # k6 load test scripts
├── infra/
│   ├── docker/                            # Dockerfiles (one per deployable service)
│   ├── k8s/                               # K8s manifests per service + namespace
│   └── terraform/
│       ├── modules/                       # vpc, rds, eks, iam ...
│       └── environments/                  # dev/ staging/ prod/
└── docs/
    ├── adr/                               # Architecture Decision Records
    ├── api/                               # OpenAPI specs (*.openapi.yaml per service)
    ├── architecture/                      # system-overview, data-model, kafka-topics
    └── runbooks/                          # Operational runbooks
```

## Slash Commands

| Command | Purpose | When to use |
|---------|---------|-------------|
| `/new-service <name>` | Scaffold entire microservice | Always — creates 15+ files |
| `/new-feature <name>` | Plan full-stack feature | Always — coordinates all layers |
| `/add-k8s-manifest <service>` | Full K8s YAML (Deployment + Service + HPA + PDB) | Always — complex YAML, easy to miss fields |
| `/add-terraform-module <name>` | Terraform module skeleton + env wiring | Always — boilerplate-heavy |
| `/add-endpoint <method> <path>` | REST endpoint boilerplate | Optional — `patterns.md` skeleton is sufficient |
| `/add-migration <service> <desc>` | Next numbered Flyway migration file | Optional — `patterns.md` skeleton is sufficient |
| `/add-kafka <topic> <event-type>` | Kafka topic + producer/consumer | Optional — `patterns.md` skeleton is sufficient |
| `/new-component <name>` | React component with TanStack Query + shadcn | Optional — `patterns.md` skeleton is sufficient |
| `/new-page <name> <route>` | TanStack Start file-based route/page | Optional — `patterns.md` skeleton is sufficient |
| `/add-adr <title>` | Architecture Decision Record from template | Optional — simple template |
| `/check-constraints` | Audit file against all project rules | Use to verify generated code |
| `/check-docker` | Audit a Dockerfile | Use to verify Dockerfiles |
| `/check-k8s` | Audit K8s manifests | Use to verify K8s YAML |

---

@.claude/rules/backend.md
@.claude/rules/frontend.md
@.claude/rules/infra.md
@.claude/rules/patterns.md
@.claude/rules/workflow.md
