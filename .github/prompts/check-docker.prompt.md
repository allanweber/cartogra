---
mode: 'agent'
description: 'Audit a Dockerfile for multi-stage build, non-root user, JVM flags, and layer ordering'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Audit a Dockerfile against Cartogra best practices: multi-stage builds, non-root user, JVM container flags, layer ordering.

**Usage:** provide `[service-or-path]` — if omitted, audits all Dockerfiles in the repo.

## Steps

1. **Find the Dockerfile**:
   - If a service name is given, look at `infra/docker/<service>/Dockerfile`
   - If a path is given, use that directly
   - If no argument, find all `Dockerfile*` files

2. **For each Dockerfile, audit**:

   ### Multi-stage build
   - [ ] Has at least 2 `FROM` stages
   - [ ] First stage uses a full JDK image (`eclipse-temurin:25-jdk-jammy` or similar)
   - [ ] Final stage uses a minimal JRE image (`eclipse-temurin:25-jre-jammy`)
   - [ ] Final stage only copies the built artifact — no source code

   ### Image tags
   - [ ] No `latest` tag in any `FROM` line — must use explicit version
   - [ ] Base image tag pins a minor/patch version

   ### Non-root user
   - [ ] `RUN useradd -m -u 1000 appuser` present
   - [ ] `USER appuser` set before `ENTRYPOINT`/`CMD`
   - [ ] UID is 1000 (not 0, not root)

   ### Layer ordering (cache efficiency)
   - [ ] Dependency descriptors copied before source code
   - [ ] Dependency resolution runs before source copy

   ### Instructions
   - [ ] `COPY` used — NEVER `ADD`
   - [ ] `.dockerignore` present: excludes `.git`, `target/`, `build/`, `*.md`, `.env`

   ### JVM container settings
   - [ ] `-XX:MaxRAMPercentage=75` in `ENV JAVA_OPTS` or `ENTRYPOINT`
   - [ ] No `-Xmx` or `-Xms` flags

   ### ENTRYPOINT
   - [ ] Exec form with `exec java ${JAVA_OPTS} -jar app.jar` (signal passthrough)
   - [ ] NOT shell form (`CMD java -jar app.jar` — traps signals)

   ### Health check
   - [ ] `HEALTHCHECK` directive present
   - [ ] Points to `/actuator/health/live`
   - [ ] Reasonable interval (`--interval=30s --timeout=3s --retries=3`)

3. **Output a checklist report** per Dockerfile:
   - PASS ✓ for each rule that passes
   - FAIL ✗ with the exact line number and content that violates the rule
   - Suggested fix for each FAIL

4. **Priority order for fixes**:
   - Critical: non-root user missing, `latest` tag, shell-form ENTRYPOINT
   - High: multi-stage missing, `-Xmx` hardcoded, `ADD` used
   - Medium: HEALTHCHECK missing, MaxRAMPercentage missing, layer order suboptimal
   - Low: `.dockerignore` incomplete
