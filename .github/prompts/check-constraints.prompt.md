---
mode: 'agent'
description: 'Audit the current file or staged changes against all Cartogra project rules'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Audit the current file or staged changes against ALL Cartogra project rules.

**Usage:** provide `[file-or-path]` or leave empty to audit all files changed since last commit.

## Steps

1. **Determine scope**:
   - If a path is given, audit that file/directory
   - Otherwise, run `git diff --name-only HEAD` to find changed files

2. **For each Java file, check**:
   - [ ] **Envelope**: REST controllers return `ApiResponse<T>` with `.data` and `.traceId` — NOT a flat object
   - [ ] **X-Trace-Id**: every `ResponseEntity` sets `X-Trace-Id` header
   - [ ] **traceId source**: extracted from `Span.current().getSpanContext().getTraceId()` — NOT hardcoded, NOT UUID
   - [ ] **No JPA**: no `@Entity`, `@GeneratedValue`, `@OneToMany`, `@ManyToOne`, `EntityManager`, `JpaRepository`
   - [ ] **Constructor injection**: no `@Autowired` field injection
   - [ ] **Records for DTOs**: request/response types are `record`, not mutable classes
   - [ ] **tenant_id**: domain tables and queries include `tenant_id`; extracted from `X-Tenant-Id` header
   - [ ] **Optional usage**: `Optional` used as return type only — never as field or parameter
   - [ ] **No null returns**: methods return `Optional<T>` or throw — never return `null`
   - [ ] **No silent exception swallowing**: every catch either logs with context or rethrows
   - [ ] **Named SQL params**: no string-concatenated SQL — always `NamedParameterJdbcTemplate` or `@Query` with `:param`
   - [ ] **No deprecated APIs**: no deprecated classes/methods; no `@SuppressWarnings`
   - [ ] **No `com.fasterxml.jackson`** imports in new code — use `tools.jackson`

3. **For each Dockerfile, check**:
   - [ ] **Multi-stage**: builder stage (JDK) → runtime stage (JRE)
   - [ ] **Non-root user**: `useradd -m -u 1000 appuser` + `USER appuser`
   - [ ] **JVM memory**: `-XX:MaxRAMPercentage=75` present — no `-Xmx`/`-Xms`
   - [ ] **ENTRYPOINT**: `exec java ${JAVA_OPTS} -jar app.jar` (exec form for signal handling)
   - [ ] **No ADD**: use `COPY` only
   - [ ] **No `latest` tag**: explicit version on all `FROM` lines
   - [ ] **HEALTHCHECK**: points to `/actuator/health/live`

4. **For each K8s manifest (YAML), check**:
   - [ ] **Resources**: `requests` AND `limits` on every container
   - [ ] **Probes**: `livenessProbe`, `readinessProbe`, `startupProbe` all present
   - [ ] **Probe paths**: `/actuator/health/live` and `/actuator/health/ready`
   - [ ] **Security context**: `runAsNonRoot: true`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
   - [ ] **Image tag**: semantic version — never `latest`
   - [ ] **PDB exists** for critical services
   - [ ] **No LoadBalancer** for internal services

5. **For each Terraform file, check**:
   - [ ] **`for_each` not `count`** for named resources
   - [ ] **Sensitive vars**: `sensitive = true` on passwords/tokens/keys
   - [ ] **Tags**: all resources tagged with `Environment`, `Project`, `ManagedBy`, `Owner`
   - [ ] **Validation blocks** on enum variables
   - [ ] **Remote state**: S3 backend + DynamoDB locking configured

6. **For each React/TypeScript file, check**:
   - [ ] **TanStack Query**: data fetching via `useQuery`/`useMutation` — not raw `fetch` outside TanStack
   - [ ] **TanStack Router**: routes with `createFileRoute` — no `createBrowserRouter`
   - [ ] **Envelope parsing**: `.data` extracted, `.error` handled — never assume flat response
   - [ ] **shadcn/ui + Tailwind**: no MUI, Ant Design, Bootstrap imports
   - [ ] **Named exports**: no `export default` on components
   - [ ] **Error + loading states** rendered

7. **Output a checklist report** grouped by file:
   - PASS ✓ for each rule that passes
   - FAIL ✗ with the specific line(s) that violate the rule
   - SKIP — for rules that don't apply to this file type

8. **If violations found**: suggest the minimal fix for each, referencing the relevant section of `.github/copilot-instructions.md`.
