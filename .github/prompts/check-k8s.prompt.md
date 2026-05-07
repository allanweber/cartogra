---
mode: 'agent'
description: 'Audit Kubernetes manifests for resource limits, probes, security context, image tags, PDB, and HPA'
---

You are working in the Cartogra monorepo. The full project rules are in `.github/copilot-instructions.md` — apply them to everything you generate.

Audit Kubernetes manifests against Cartogra rules: resource limits, probes, security context, image tags, PDB, HPA.

**Usage:** provide `[service-or-path]` — if omitted, audits all YAML files under `infra/k8s/`.

## Steps

1. **Find manifests**:
   - If a service name: look in `infra/k8s/<service>/`
   - If a path: use directly
   - If no argument: audit all `*.yaml` files under `infra/k8s/`

2. **For each Deployment, audit**:

   ### Resources
   - [ ] Every container has `resources.requests.cpu` and `resources.requests.memory`
   - [ ] Every container has `resources.limits.cpu` and `resources.limits.memory`
   - [ ] `memory limit == memory request` (Guaranteed QoS)
   - [ ] `cpu limit ≈ 2× cpu request`

   ### Probes (all 3 required)
   - [ ] `startupProbe` present
   - [ ] `livenessProbe` present
   - [ ] `readinessProbe` present
   - [ ] Liveness and startup probe path: `/actuator/health/live`
   - [ ] Readiness probe path: `/actuator/health/ready`

   ### Security context (pod level)
   - [ ] `securityContext.runAsNonRoot: true`
   - [ ] `securityContext.runAsUser: 1000`
   - [ ] `securityContext.fsGroup` set

   ### Security context (container level)
   - [ ] `securityContext.allowPrivilegeEscalation: false`
   - [ ] `securityContext.readOnlyRootFilesystem: true`
   - [ ] `securityContext.capabilities.drop: [ALL]`
   - [ ] No `securityContext.privileged: true`
   - [ ] No `runAsUser: 0`

   ### /tmp volume
   - [ ] If `readOnlyRootFilesystem: true`, a `/tmp` volumeMount backed by `emptyDir` exists

   ### Image
   - [ ] Image tag is semantic version (`v1.x.x`) — NEVER `latest` or untagged

   ### Rolling update strategy
   - [ ] `strategy.type: RollingUpdate`
   - [ ] `maxUnavailable: 0`
   - [ ] `maxSurge: 1`

   ### Labels (all required)
   - [ ] `app`, `version`, `environment`, `team` labels present

3. **For each Service, audit**:
   - [ ] Type is `ClusterIP` for internal services — NEVER `LoadBalancer`

4. **For each HPA, audit**:
   - [ ] `minReplicas ≥ PDB.minAvailable` for the same service
   - [ ] Both CPU and memory metrics configured
   - [ ] `behavior.scaleDown.stabilizationWindowSeconds: 300`

5. **For each PDB, audit**:
   - [ ] Exists for every critical service
   - [ ] `minAvailable ≥ 2`

6. **For each Secret reference, audit**:
   - [ ] Secrets mounted as volumes — NOT passed as `env` from `secretKeyRef`
   - [ ] No literal Secret manifests with `data:` base64 values committed to Git

7. **Output a checklist report** per manifest file:
   - PASS ✓ / FAIL ✗ / N/A for each rule
   - On FAIL: `metadata.name`, rule violated, field reference, suggested fix
   - Summary at the end: total pass/fail, critical issues highlighted
