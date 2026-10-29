# 021 — Phase 4: a product a stranger can try

Gate #130. Source: `/grill-with-docs` session 2026-10-27/28. Roadmap `docs/roadmap.md` Phase 4 (now ≈6 weeks, 7.1 pulled in).

**Branch: `docs/phase-4-plan` (all steps).** Run one step at a time when called; commit each step to this branch. Build green (`./gradlew build`, frontend tests) before the next step. Rebase on main between steps if main moves.

Order: app code first (testable on compose) → charts → deploy → ops → gate.

## Checklist

- [ ] 1. Collector in compose (#192)
- [ ] 2. Audit trail (#125)
- [ ] 3. Dashboard on real data (#126)
- [ ] 4. Guest mode + signup off (#128, signup part of #129)
- [ ] 5. Helm charts (#148)
- [ ] 6. Deploy pipeline + in-cluster Acme (#129, code)
- [ ] 7. Ops — public host (#129)
- [ ] 8. Gate (#130)

## 1. Collector in compose (#192)

As written (compose + `E2E_OBSERVED=true` in CI). Plus a demo collector config without Tempo/Loki/Prometheus exporters, consumed by the Helm chart. Collector is never exposed via ingress.

Done when: `pnpm acme verify` observed-edge check passes against compose.

## 2. Audit trail (#125)

ADR-0017 → Accepted, topic `cartogra.platform.audit.recorded`.

- Registry owns `audit_events`: `tenant_id`, `entity_type`, `entity_id`, `action`, `actor_kind` (`user` | `system`), `actor_id` (null for `system`), `payload` JSONB, `occurred_at`, `event_id` (unique, consumer idempotency). RLS, GIN on `payload`, index `(tenant_id, occurred_at DESC)`. Kept forever (no purge; Phase 8 GDPR anonymises).
- `AuditEventPort` + `AuditEvent` record in `shared:common`. Registry: Jdbc adapter, same transaction as the mutation. Topology, ingestion, gateway: Kafka adapter → `cartogra.platform.audit.recorded`; registry consumes idempotently on `event_id`.
- Gateway gains a Kafka producer (it has none today); publish off the request path.
- Payload = changed fields `{changes: {field: {from, to}}}` for updates, key fields for create/delete. Per-entity field allowlist — tokens, kubeconfigs, password hashes never enter.
- Emit on **state changes only**:
  - registry: service create/update/delete, discovery merge, team CRUD + membership, ownership, tier.
  - topology: declared edge CRUD, observed edge first seen, drift detected/resolved. NOT per span / `last_seen` bumps.
  - ingestion: SCM connection + cluster CRUD, sync completed/failed.
  - gateway: user invited / role changed / disabled / enabled, OIDC config. NOT logins, refreshes, guest mints.
- `GET /v1/audit-events`: tenant-scoped, paginated (`PageResult`), filters `entityType`, `entityId`, `actorKind`, `from`/`to`. Readable by **every role incl. GUEST** (not admin-only).
- `timeline.tsx` drops `MOCK_TIMELINE`.
- Tests: Jdbc adapter IT; consumer idempotency IT (same `event_id` twice → one row); per-service IT asserting one representative mutation emits; payload allowlist unit test (secret field never present).

## 3. Dashboard on real data (#126)

- Only `ActivityList` still reads `MOCK_TIMELINE` (health summary + teams already real); wire it to `GET /v1/audit-events?size=5`. Top risks from 2.5 if not yet wired.
- `dashboard.tsx` has no `MOCK_*`; Vitest loading / error / empty.

## 4. Guest mode + signup off (#128, signup part of #129)

ADR-0019 → Accepted + amended (no ADR-0030 for this).

- `POST /api/auth/guest` mints a JWT: `sub` = random UUID (not `guest-` prefix — downstream parses `X-User-Id` as UUID), `tid` = demo tenant (config `app.guest.tenant-id`), `roles=["GUEST"]`, 60 min, httpOnly cookie, no refresh token. Disabled when `app.guest.tenant-id` is unset.
- **Gateway deny (primary guard)**: GUEST + method not GET/HEAD/OPTIONS → 403 envelope. Allowlist: `/api/auth/guest`, `/api/auth/logout`. Fail-closed for future endpoints.
- **Rate limits**: `/api/auth/guest` per IP (≈5/min); guest API calls bucketed per guest `sub`, not the shared demo-tenant bucket.
- **Backend sweep (secondary, fixes VIEWER today)**: topology's 6 write endpoints get MEMBER/ADMIN guards (0 today); registry `TeamService` drops `TEAM_OWNER` for ADMIN-or-member per ADR-0025; every write in registry/topology/ingestion checked.
- **Frontend**: `/demo` route mints and lands on dashboard; login page "Try the demo"; on 401 in a guest session re-mint silently and retry; persistent "Read-only demo" banner; write controls visible but disabled with tooltip; Settings/admin hidden from nav.
- **Signup off**: `app.signup.enabled=false`: `POST /api/auth/register` → 403 `SIGNUP_DISABLED`; `OAuthService.resolveNewTenantUser` refuses new tenants (existing users + invites unaffected); frontend hides signup. IT covers both guards.
- Tests: IT enumerates every mapped write route (from handler mappings, not a hand list) with a guest token → 403; guest reads 200; IT for re-mint and per-sub rate limit. E2E `frontend/e2e/guest-demo.spec.ts`: `/demo` → dashboard activity → blast radius → disabled buttons → direct API write 403.

## 5. Helm charts (#148, 7.1 pulled into Phase 4)

- Per-service charts (gateway, registry, topology, ingestion, frontend, collector) + umbrella; Postgres, Kafka, Valkey as single-replica StatefulSets (local-path PVs).
- infra.md probes, resources (requests + limits), pod/container securityContext, `/tmp` emptyDir, labels applied everywhere.
- HA (replicas, PDB, HPA) via values; `values-demo.yaml` sets replicas 1, PDB/HPA off — ADR-0030.
- Secrets mounted as volumes from Sealed Secrets.
- **Cleanup** (charts replace them): port probes/resources/securityContext from `infra/k8s/base` into the charts, then delete `infra/k8s/base`, `infra/k8s/overlays`, `infra/k8s/infra/otel-collector`, `infra/k8s/infra/namespace.yml`, and `infra/k8s/cluster/` (personal kubeadm lab, not deploy code). Keep `infra/k8s/infra/helm/*` (monitoring values) for 7.6. Repoint `docs/runbooks/quickstart.md` §53 to the charts.
- Multi-arch images (`linux/amd64,linux/arm64`) via `docker buildx` in CI, Acme fake-service image included.
- `values-demo.yaml` sized for 8GB per ADR-0030: JVMs 512Mi limit, `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k`; Kafka `-Xmx384m`; Postgres `shared_buffers=128MB`.

Done when: `helm lint` + `helm template` clean; umbrella installs on local k3d.

## 6. Deploy pipeline + in-cluster Acme (#129, code)

- CI: main pushes SHA-tagged images to GHCR; `workflow_dispatch` job runs `helm upgrade` with a kubeconfig secret.
- Acme live in-cluster: fake services as pods in namespace `acme`; Helm post-install bootstrap Job creates Acme tenant (fixed UUID), registers the in-cluster cluster via a read-only ServiceAccount kubeconfig, connects the GitHub mirror, runs `acme load`.
- Nightly `pg_dump` CronJob to object storage.
- Rewrite `docs/runbooks/incident-response.md` for the demo host and add the in-cluster ServiceAccount case to `docs/runbooks/kubernetes-cluster-config.md`.
- Rewrite `docs/runbooks/deployment.md`: VM → k3s → sealed-secrets key restore → helm install → bootstrap Job → verify.

Done when: chart + bootstrap Job run on local k3d and Acme is discovered.

## 7. Ops — public host (#129)

Human steps, following the rewritten `deployment.md` (doubles as its test):

- Hetzner **CAX21 (ARM, 4 vCPU, 8GB)**, resize to CAX31 only on measured pressure; single-node k3s, Traefik (bundled) + cert-manager Let's Encrypt on `cartogra.dev`.
- Sealed Secrets controller; encrypted `SealedSecret`s committed.
- GitHub mirror: `pnpm acme repos --provider github` pushes the 21 repos to a public org; demo syncs with a read-only public-repos token.
- Kubeconfig CI secret; first `helm install`.
- External uptime check on `/actuator/health/ready` through ingress.

## 8. Gate (#130)

Roadmap Phase 4 gate + additions below; open PR from `docs/phase-4-plan`.

## E2E

- New `guest-demo.spec.ts` (step 4).
- `demo-flow.spec.ts` asserts loader writes appear on Timeline (step 2).

## Gate additions (on top of roadmap gate)

- `/demo` gets a stranger in with no credentials; no signup path on the public host.
- Audit events from all four services; readable by GUEST.
- Runbook rebuilds the k3s host from scratch, including the bootstrap Job.
