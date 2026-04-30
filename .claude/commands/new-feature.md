You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Plan and scaffold a full-stack feature across backend service(s), frontend, migrations, Kafka events, and K8s manifests.

Arguments: $ARGUMENTS
(Expected: `<feature-name> <service>` — e.g., `/new-feature service-health-dashboard registry`)

## Steps

1. **Parse arguments**: feature name, primary service

2. **Produce a feature plan** covering all layers:

   ### Backend
   - Domain entity/aggregate in `domain/`
   - Flyway migration in `db/migration/` (next V number, UUID PK, tenant_id, TIMESTAMPTZ, soft delete)
   - Repository interface + Spring Data JDBC impl in `infrastructure/`
   - Use case interface in `application/` + service impl
   - REST controller with envelope response + `X-Trace-Id` header in `api/`
   - Request/response records (immutable)
   - Error handling in `GlobalExceptionHandler`

   ### Kafka (if the feature produces or consumes events)
   - Event envelope record with `event_id (UUIDv5)`, `event_type`, `entity_id`, `tenant_id`, `timestamp`, `version`, `correlation_id`, `payload`
   - Producer with W3C `traceparent` injection
   - Consumer with `traceparent` extraction
   - Topic name: `cartogra.<domain>.<entity>.<event>`
   - Only add topic if BOTH producer and consumer exist (no speculative topics)

   ### Frontend
   - TanStack Start route/page (`/new-page`) with AppLayout + TanStack Query
   - React components (`/new-component`) with shadcn/ui, TanStack Table/Forms where applicable, envelope parsing, loading + error states
   - Route created in `frontend/src/routes/` with `createFileRoute`
   - Sidebar nav link in `frontend/src/components/layout/Sidebar.tsx` (if top-level feature)

   ### Infrastructure
   - K8s manifests: Deployment + Service (ClusterIP) + HPA + PDB + ConfigMap
   - Security context: `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities.drop ALL`
   - All 3 probes: liveness + readiness + startup → `/actuator/health/live` & `/actuator/health/ready`
   - Resource requests + limits (limits ≈ 2× requests; memory limit = memory request)

3. **Scaffold each layer** following the templates in AGENTS.md:
   - Use `/add-migration`, `/add-endpoint`, `/add-kafka`, `/new-component`, `/new-page`, `/add-k8s-manifest` patterns
   - All files must pass `/check-constraints` rules

4. **Verify rules checklist before finishing:**
   - [ ] Envelope: `{"data": ..., "traceId": "..."}` + `X-Trace-Id` on every response
   - [ ] tenant_id on all domain tables and queries
   - [ ] Flyway migration: UUID PK, TIMESTAMPTZ, soft delete, RLS scaffold
   - [ ] Kafka traceparent propagated (if Kafka used)
   - [ ] Frontend: TanStack Start + TanStack Router + TanStack Query + TanStack Table/Forms + shadcn + envelope parsing
   - [ ] K8s: resources, probes, security context, PDB, non-latest image tag
   - [ ] No JPA, no @Autowired field injection, no null returns, no raw fetch
