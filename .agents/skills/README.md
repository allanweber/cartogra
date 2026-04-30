# Cartogra — Available Workflows / Skills

This file documents all available AI-assisted workflows for the Cartogra monorepo.
These are executable as Claude Code slash commands (`.claude/commands/`).
Other AI tools (Cursor, Copilot, etc.) can use this file as reference context.

---

## Scaffolding

### `/new-service <name>`
Scaffold a new Spring Boot microservice: Gradle module registration, hexagonal package structure (`api/`, `domain/`, `application/`, `infrastructure/`, `config/`), `Application.java`, `application.yml`, first Flyway migration, `GlobalExceptionHandler`, multi-stage Dockerfile.

### `/new-feature <feature-name> <service>`
Full-stack feature planning and scaffolding across all layers: domain entity, Flyway migration, use case interface + implementation, REST controller with envelope response, Kafka events (if applicable), React page + components, K8s manifests. Runs constraint checks on all generated files.

### `/add-adr <title>`
Create a numbered Architecture Decision Record in `docs/adr/`. Auto-increments the sequence number, sets today's date, status `Proposed`, and populates the Context / Decision / Consequences template.

---

## Backend — Spring Boot

### `/add-migration <service> <description>`
Create the next Flyway migration file for a service. Auto-numbers (`V00N__description.sql`), enforces `TIMESTAMPTZ`, `UUID` PKs via `gen_random_uuid()`, `tenant_id UUID NOT NULL`, `deleted_at TIMESTAMPTZ` soft delete, and scaffolds an RLS policy.

### `/add-endpoint <service> <HTTP-METHOD> <path> [description]`
Add a REST endpoint to an existing service. Generates request/response records, controller method with envelope response (`{"data": ..., "traceId": "..."}`) and `X-Trace-Id` header, use case interface, and `GlobalExceptionHandler` error case.

### `/add-kafka <service> <topic-suffix> <event-type> [producer|consumer|both]`
Scaffold Kafka configuration and boilerplate. Adds topic config to `application.yml`, creates the event envelope record, and generates producer (with W3C `traceparent` injection) and/or consumer (with `traceparent` extraction). Topic naming: `cartogra.<domain>.<entity>.<event>`.

---

## Frontend — React

### `/new-page <PageName> <route-path>`
Create a new React page with `AppLayout` sidebar wrapper, TanStack Query data loading, envelope parsing (`.data` / `.error`), loading skeletons, and error alerts. Registers the route in `frontend/src/router.tsx`.

### `/new-component <ComponentName> [api-endpoint]`
Create a React component with TanStack Query (`useQuery`), shadcn/ui cards, Tailwind styling, envelope parsing, loading skeleton, and error alert. Named export, no default export.

---

## Infrastructure

### `/add-k8s-manifest <service> [namespace]`
Scaffold Kubernetes manifests for a service: `Deployment` (security context, all 3 probes, resource requests+limits), `Service` (ClusterIP), `HPA` (CPU + memory, scaleDown stabilization 300s), `PDB` (`minAvailable: 2`), `ConfigMap`. All security context rules applied: `runAsNonRoot`, `readOnlyRootFilesystem`, `capabilities.drop ALL`, `/tmp` as `emptyDir`.

### `/add-terraform-module <module-name> [environment]`
Scaffold a Terraform module under `terraform/modules/<name>/`. Generates `variables.tf` (with enum validation), `main.tf` (S3 remote state, `common_tags` locals), `outputs.tf`, and wires it into `terraform/environments/<env>/main.tf`.

---

## Auditing / Checks

### `/check-constraints [file-or-path]`
Audit files against ALL Cartogra project rules: envelope format, `X-Trace-Id`, no JPA, constructor injection, records for DTOs, `tenant_id` presence, no null returns, named SQL params, TanStack Query, shadcn/ui, envelope parsing, Dockerfile rules, K8s rules, Terraform rules. Outputs pass/fail checklist per file with suggested fixes.

### `/check-docker [service-or-path]`
Audit Dockerfiles: multi-stage build, non-root user (UID 1000), `-XX:MaxRAMPercentage=75` (no `-Xmx`/`-Xms`), `exec java` ENTRYPOINT (not shell form), `COPY` not `ADD`, no `latest` tag, `HEALTHCHECK` pointing to `/actuator/health/live`, `.dockerignore` present.

### `/check-k8s [service-or-path]`
Audit K8s manifests: resource requests+limits (memory limit = memory request), all 3 probes with correct paths, pod + container security context, semantic image tag (no `latest`), `ClusterIP` not `LoadBalancer`, PDB `minAvailable ≥ 2`, HPA with scaleDown stabilization, required labels (`app`, `version`, `environment`, `team`).

---

## Key Rules Summary

See `AGENTS.md` at the project root for the full authoritative rule set. Quick reference:

| Area | Critical rule |
|------|--------------|
| HTTP responses | Always wrap in `{"data": ..., "traceId": "..."}` + `X-Trace-Id` header |
| ORM | Spring Data JDBC only — **never JPA/Hibernate** |
| DB | UUID PKs, TIMESTAMPTZ, tenant_id on all tables, soft deletes |
| Kafka | UUIDv5 event_id, traceparent in headers, key = entity UUID |
| Auth | X-Tenant-Id injected by gateway — strip from client requests |
| React | TanStack Query + shadcn/ui + Tailwind — no MUI/Bootstrap/raw fetch |
| Docker | Multi-stage, non-root UID 1000, MaxRAMPercentage=75, exec form |
| K8s | Resources + limits, 3 probes, security context, PDB, no `latest` |
| Terraform | for_each not count, S3+DynamoDB state, sensitive = true for secrets |
