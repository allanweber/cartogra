You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Audit the current file or staged changes against ALL Cartogra project rules.

Arguments: $ARGUMENTS
(Expected: `[file-or-path]` — e.g., `/check-constraints services/registry/src/main/java/io/cartogra/registry/api/ServiceController.java`)
(If no argument provided, audit all files changed since the last commit.)

## Steps

1. **Determine scope**:
   - If a path is given, audit that file/directory
   - Otherwise, run `git diff --name-only HEAD` to find changed files

2. **For each Java file, check**:
   - [ ] **Envelope**: REST controllers return `ApiResponse<T>` with `.data` and `.traceId` — NOT a flat object
   - [ ] **X-Trace-Id**: every `ResponseEntity` sets `X-Trace-Id` header
   - [ ] **traceId source**: extracted from `Span.current().getSpanContext().getTraceId()` — NOT hardcoded, NOT UUID
   - [ ] **No JPA**: no `@Entity`, `@GeneratedValue`, `@OneToMany`, `@ManyToOne`, `EntityManager`, `JpaRepository` — Spring Data JDBC only
   - [ ] **Constructor injection**: no `@Autowired` field injection — `@RequiredArgsConstructor` only
   - [ ] **Records for DTOs**: request/response types are `record`, not mutable classes
   - [ ] **tenant_id**: domain tables and queries include `tenant_id`; extracted from `X-Tenant-Id` header
   - [ ] **Optional usage**: `Optional` used as return type only — never as field or parameter
   - [ ] **No null returns**: methods return `Optional<T>` or throw — never return `null`
   - [ ] **No silent exception swallowing**: every catch either logs with context or rethrows
   - [ ] **Named SQL params**: no string-concatenated SQL — always `NamedParameterJdbcTemplate` or `@Query` with `:param`
   - [ ] **No checked exceptions for non-IO**: only IO/network → checked; logic errors → unchecked

3. **For each Dockerfile, check**:
   - [ ] **Multi-stage**: builder stage (JDK) → runtime stage (JRE/distroless)
   - [ ] **Non-root user**: `useradd -m -u 1000 appuser` + `USER appuser`
   - [ ] **JVM memory**: `-XX:MaxRAMPercentage=75` present — no `-Xmx`/`-Xms`
   - [ ] **ENTRYPOINT**: `exec java ${JAVA_OPTS} -jar app.jar` (exec form for signal handling)
   - [ ] **No ADD**: use `COPY` only
   - [ ] **No `latest` tag**: `FROM eclipse-temurin:25-jre` style with explicit tag
   - [ ] **HEALTHCHECK**: points to `/actuator/health/live`

4. **For each K8s manifest (YAML), check**:
   - [ ] **Resources**: `requests` AND `limits` on every container — never omit either
   - [ ] **Probes**: `livenessProbe`, `readinessProbe`, `startupProbe` all present
   - [ ] **Probe paths**: `/actuator/health/live` and `/actuator/health/ready`
   - [ ] **Security context**: `runAsNonRoot: true`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`
   - [ ] **Image tag**: semantic version (`v1.2.3`) — never `latest`
   - [ ] **PDB exists** for critical services
   - [ ] **No LoadBalancer** for internal services — use `ClusterIP`

5. **For each Terraform file, check**:
   - [ ] **`for_each` not `count`** for named resources
   - [ ] **Sensitive vars**: `sensitive = true` on passwords/tokens/keys
   - [ ] **Tags**: all resources tagged with `Environment`, `Project`, `ManagedBy`, `Owner`
   - [ ] **Validation blocks** on enum variables (e.g., environment names)
   - [ ] **Remote state**: S3 backend + DynamoDB locking configured

6. **For each React/TypeScript file, check**:
   - [ ] **TanStack Query**: data fetching via `useQuery`/`useMutation` — not raw `fetch` or `axios` outside TanStack
   - [ ] **TanStack Router**: routes created with file-based routing (`src/routes`) — no `createBrowserRouter`
   - [ ] **TanStack Table** used for table/grid UIs — no heavy grid framework
   - [ ] **TanStack Forms** used for non-trivial forms — no Formik or ad-hoc form state
   - [ ] **Envelope parsing**: `.data` extracted, `.error` handled — never assume flat response
   - [ ] **shadcn/ui + Tailwind**: no MUI, Ant Design, Bootstrap imports
   - [ ] **Named exports**: no `export default` on components
   - [ ] **Error state rendered**: loading and error states shown to user

7. **Output a checklist report** grouped by file, with:
   - PASS ✓ for each rule that passes
   - FAIL ✗ with the specific line(s) that violate the rule
   - SKIP — for rules that don't apply to this file type

8. **If violations found**: suggest the minimal fix for each, referencing the specific AGENTS.md section.
