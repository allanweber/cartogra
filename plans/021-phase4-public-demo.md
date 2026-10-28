# 021 — Phase 4: a product a stranger can try

Gate #130. Source: `/grill-with-docs` session 2026-10-27/28. Roadmap `docs/roadmap.md` Phase 4 (now ≈6 weeks, 7.1 pulled in).
Branches: `issues/<n>-<slug>` per story. No new issues — the six below carry everything.

Execution order — sections below follow it:

1. Infra track (critical path): #192 → #148 → #129.
2. Product track, in parallel once #192 is merged: #125 → #126 → #128.

## #192 — 4.4 Collector (`issues/192-collector-compose`)

As written (compose + `E2E_OBSERVED=true` in CI). Plus a demo collector config without Tempo/Loki/Prometheus exporters, consumed by the Helm chart. Collector is never exposed via ingress.

## #148 — 7.1 Helm charts, pulled into Phase 4 (`issues/148-helm-charts`)

- Per-service charts (gateway, registry, topology, ingestion, frontend, collector) + umbrella; Postgres, Kafka, Valkey as single-replica StatefulSets (local-path PVs).
- infra.md probes, resources (requests + limits), pod/container securityContext, `/tmp` emptyDir, labels applied everywhere.
- HA (replicas, PDB, HPA) via values; `values-demo.yaml` sets replicas 1, PDB/HPA off — ADR-0030.
- Secrets mounted as volumes from Sealed Secrets.
- **Cleanup** (charts replace them): port probes/resources/securityContext from `infra/k8s/base` into the charts, then delete `infra/k8s/base`, `infra/k8s/overlays`, `infra/k8s/infra/otel-collector`, `infra/k8s/infra/namespace.yml`, and `infra/k8s/cluster/` (personal kubeadm lab, not deploy code). Keep `infra/k8s/infra/helm/*` (monitoring values) for 7.6. Repoint `docs/runbooks/quickstart.md` §53 to the charts.
- Multi-arch images (`linux/amd64,linux/arm64`) via `docker buildx` in CI, Acme fake-service image included.
- `values-demo.yaml` sized for 8GB per ADR-0030: JVMs 512Mi limit, `-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k`; Kafka `-Xmx384m`; Postgres `shared_buffers=128MB`.

## #129 — 4.5 One public environment (`issues/129-public-environment`)

- Hetzner **CAX21 (ARM, 4 vCPU, 8GB)**, resize to CAX31 only on measured pressure; single-node k3s, Traefik (bundled) + cert-manager Let's Encrypt on `cartogra.dev`.
- Sealed Secrets controller; encrypted `SealedSecret`s committed.
- CI: main pushes SHA-tagged images to GHCR; `workflow_dispatch` job runs `helm upgrade` with a kubeconfig secret.
- Acme live in-cluster: fake services as pods in namespace `acme`; Helm post-install bootstrap Job creates Acme tenant (fixed UUID), registers the in-cluster cluster via a read-only ServiceAccount kubeconfig, connects the GitHub mirror, runs `acme load`.
- GitHub mirror: `pnpm acme repos --provider github` pushes the 21 repos to a public org; demo syncs with a read-only public-repos token.
- `app.signup.enabled=false`: `POST /api/auth/register` → 403 `SIGNUP_DISABLED`; `OAuthService.resolveNewTenantUser` refuses new tenants (existing users + invites unaffected); frontend hides signup. IT covers both guards.
- Nightly `pg_dump` CronJob to object storage. External uptime check on `/actuator/health/ready` through ingress.
- Rewrite `docs/runbooks/incident-response.md` for the demo host and add the in-cluster ServiceAccount case to `docs/runbooks/kubernetes-cluster-config.md`.
- Rewrite `docs/runbooks/deployment.md`: VM → k3s → sealed-secrets key restore → helm install → bootstrap Job → verify.

## #125 — 4.1 Audit trail (`issues/125-audit-trail`)

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

## #126 — 4.2 Dashboard on real data (`issues/126-dashboard-real-data`)

- Only `ActivityList` still reads `MOCK_TIMELINE` (health summary + teams already real); wire it to `GET /v1/audit-events?size=5`. Top risks from 2.5 if not yet wired.
- `dashboard.tsx` has no `MOCK_*`; Vitest loading / error / empty.

## #128 — 4.3 Guest read-only mode (`issues/128-guest-mode`)

ADR-0019 → Accepted + amended (no ADR-0030 for this).

- `POST /api/auth/guest` mints a JWT: `sub` = random UUID (not `guest-` prefix — downstream parses `X-User-Id` as UUID), `tid` = demo tenant (config `app.guest.tenant-id`), `roles=["GUEST"]`, 60 min, httpOnly cookie, no refresh token. Disabled when `app.guest.tenant-id` is unset.
- **Gateway deny (primary guard)**: GUEST + method not GET/HEAD/OPTIONS → 403 envelope. Allowlist: `/api/auth/guest`, `/api/auth/logout`. Fail-closed for future endpoints.
- **Rate limits**: `/api/auth/guest` per IP (≈5/min); guest API calls bucketed per guest `sub`, not the shared demo-tenant bucket.
- **Backend sweep (secondary, fixes VIEWER today)**: topology's 6 write endpoints get MEMBER/ADMIN guards (0 today); registry `TeamService` drops `TEAM_OWNER` for ADMIN-or-member per ADR-0025; every write in registry/topology/ingestion checked.
- **Frontend**: `/demo` route mints and lands on dashboard; login page "Try the demo"; on 401 in a guest session re-mint silently and retry; persistent "Read-only demo" banner; write controls visible but disabled with tooltip; Settings/admin hidden from nav.
- Tests: IT enumerates every mapped write route (from handler mappings, not a hand list) with a guest token → 403; guest reads 200; IT for re-mint and per-sub rate limit. E2E `frontend/e2e/guest-demo.spec.ts`: `/demo` → dashboard activity → blast radius → disabled buttons → direct API write 403.

## E2E

- New `guest-demo.spec.ts` (#128).
- `demo-flow.spec.ts` asserts loader writes appear on Timeline (#125).

## Gate additions (on top of roadmap gate)

- `/demo` gets a stranger in with no credentials; no signup path on the public host.
- Audit events from all four services; readable by GUEST.
- Runbook rebuilds the k3s host from scratch, including the bootstrap Job.
