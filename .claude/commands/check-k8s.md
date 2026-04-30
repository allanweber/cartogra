You are working in the Cartogra monorepo. Read AGENTS.md for all project rules before proceeding.

Audit Kubernetes manifests against Cartogra rules: resource limits, probes, security context, image tags, PDB, HPA.

Arguments: $ARGUMENTS
(Expected: `[service-or-path]` — e.g., `/check-k8s registry` or `/check-k8s k8s/registry/`)
(If no argument, audit all YAML files under `k8s/`.)

## Steps

1. **Find manifests**:
   - If a service name is given, look in `k8s/<service>/`
   - If a path is given, use that directly
   - If no argument, audit all `*.yaml` files under `k8s/`

2. **For each Deployment, audit**:

   ### Resources
   - [ ] Every container has `resources.requests.cpu` and `resources.requests.memory`
   - [ ] Every container has `resources.limits.cpu` and `resources.limits.memory`
   - [ ] `memory limit == memory request` (Guaranteed QoS — prevents OOM-kill surprise)
   - [ ] `cpu limit ≈ 2× cpu request` (headroom without over-provisioning)

   ### Probes (all 3 required)
   - [ ] `startupProbe` present (prevents premature liveness kills during slow JVM startup)
   - [ ] `livenessProbe` present (restarts stuck pods)
   - [ ] `readinessProbe` present (gates traffic)
   - [ ] Liveness and startup probe path: `/actuator/health/live`
   - [ ] Readiness probe path: `/actuator/health/ready`

   ### Security context (pod level)
   - [ ] `securityContext.runAsNonRoot: true`
   - [ ] `securityContext.runAsUser: 1000` (matches Dockerfile UID)
   - [ ] `securityContext.fsGroup` set

   ### Security context (container level)
   - [ ] `securityContext.allowPrivilegeEscalation: false`
   - [ ] `securityContext.readOnlyRootFilesystem: true`
   - [ ] `securityContext.capabilities.drop: [ALL]`
   - [ ] No `securityContext.privileged: true`
   - [ ] No `runAsUser: 0` (root)

   ### /tmp volume
   - [ ] If `readOnlyRootFilesystem: true`, a `volumeMount` for `/tmp` backed by `emptyDir` must exist

   ### Image
   - [ ] Image tag is semantic version (`v1.x.x`) — NEVER `latest` or untagged
   - [ ] Image digest (optional but preferred for prod)

   ### Rolling update strategy
   - [ ] `strategy.type: RollingUpdate`
   - [ ] `maxUnavailable: 0` (zero-downtime)
   - [ ] `maxSurge: 1`

   ### Labels (all required)
   - [ ] `app` label present
   - [ ] `version` label present
   - [ ] `environment` label present
   - [ ] `team` label present

3. **For each Service, audit**:
   - [ ] Type is `ClusterIP` for internal services — NEVER `LoadBalancer`
   - [ ] `NodePort` only if explicitly documented as external

4. **For each HPA, audit**:
   - [ ] `minReplicas ≥ PDB.minAvailable` for the same service
   - [ ] Both CPU and memory metrics configured
   - [ ] `behavior.scaleDown.stabilizationWindowSeconds: 300` (prevents flapping)

5. **For each PDB, audit**:
   - [ ] Exists for every critical service
   - [ ] `minAvailable ≥ 2`

6. **For each Secret reference, audit**:
   - [ ] Secrets mounted as volumes — NOT passed as `env` from `secretKeyRef` inline in manifests committed to Git
   - [ ] No literal Secret manifests with `data:` base64 values committed

7. **Output a checklist report** per manifest file:
   - PASS ✓ / FAIL ✗ / N/A for each rule
   - On FAIL: exact `metadata.name`, rule violated, line or field reference, suggested fix
   - Summary at the end: total pass/fail count, critical issues highlighted
