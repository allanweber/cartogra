You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Audit a Dockerfile against Cartogra best practices: multi-stage builds, non-root user, JVM container flags, layer ordering.

Arguments: $ARGUMENTS
(Expected: `[service-or-path]` — e.g., `/check-docker registry` or `/check-docker services/registry/Dockerfile`)
(If no argument, audit all Dockerfiles found in the repo.)

## Steps

1. **Find the Dockerfile**:
   - If a service name is given, look at `services/<service>/Dockerfile`
   - If a path is given, use that directly
   - If no argument, find all `Dockerfile*` files with `find . -name "Dockerfile*"`

2. **For each Dockerfile, audit these rules**:

   ### Multi-stage build
   - [ ] Has at least 2 `FROM` stages
   - [ ] First stage uses a full JDK image (e.g., `eclipse-temurin:25-jdk` or `eclipse-temurin:25-jdk-alpine`)
   - [ ] Final stage uses a minimal JRE or distroless image (e.g., `eclipse-temurin:25-jre`, `gcr.io/distroless/java25`)
   - [ ] Final stage only copies the built artifact from builder — no source code

   ### Image tags
   - [ ] No `latest` tag in any `FROM` line — must use explicit version (e.g., `eclipse-temurin:25-jre`)
   - [ ] Base image tag pins a minor/patch version — not just a major

   ### Non-root user
   - [ ] `RUN useradd -m -u 1000 appuser` (or equivalent) present
   - [ ] `USER appuser` set before `ENTRYPOINT`/`CMD`
   - [ ] UID is 1000 (not 0, not root)

   ### Layer ordering (cache efficiency)
   - [ ] Base image → system deps → create user → copy dependency descriptors → fetch deps → copy source → build → runtime copy
   - [ ] Dependency resolution (`COPY build.gradle* ./` + `RUN gradle dependencies`) before source copy

   ### Instructions
   - [ ] `COPY` used — NEVER `ADD` (ADD has silent remote fetch and tar-extraction side effects)
   - [ ] `.dockerignore` present (check repo root): should exclude `.git`, `target/`, `build/`, `*.md`, `.env`

   ### JVM container settings
   - [ ] `-XX:MaxRAMPercentage=75` present in `ENV JAVA_OPTS` or `ENTRYPOINT`
   - [ ] No `-Xmx` or `-Xms` flags (container-unaware; defeats MaxRAMPercentage)

   ### ENTRYPOINT / CMD
   - [ ] Uses `exec` form: `ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]`
     or `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]`
   - [ ] NOT shell form: `CMD java -jar app.jar` (shell form traps signals, prevents graceful shutdown)

   ### Health check
   - [ ] `HEALTHCHECK` directive present
   - [ ] Points to `/actuator/health/live`
   - [ ] Reasonable interval (e.g., `--interval=30s --timeout=5s --retries=3`)

3. **Output a checklist report** per Dockerfile:
   - PASS ✓ for each rule that passes
   - FAIL ✗ with the exact line number and content that violates the rule
   - Suggested fix for each FAIL

4. **Priority order for fixes** (if multiple failures):
   - Critical: non-root user missing, `latest` tag, shell-form ENTRYPOINT
   - High: multi-stage missing, `-Xmx` hardcoded, `ADD` used
   - Medium: HEALTHCHECK missing, MaxRAMPercentage missing, layer order suboptimal
   - Low: `.dockerignore` incomplete
