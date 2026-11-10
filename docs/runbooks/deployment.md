# Runbook: Deployment

The only running environment is the **public demo**: one Hetzner ARM VM with single-node k3s
([ADR-0030](../adr/0030-demo-on-single-node-k3s.md)). This runbook builds it from nothing and doubles as the
rebuild procedure (section 11). Charts: `infra/helm/cartogra` (the product) and `infra/helm/acme` (Acme Fintech,
the simulated customer the demo shows).

```
push to main ─▶ images.yml: ghcr.io/<owner>/cartogra-<image>:<sha> (amd64 + arm64)
manual       ─▶ deploy.yml: helm upgrade cartogra + acme at <sha> ─▶ acme bootstrap Job
```

| Piece | Where |
|---|---|
| VM | Hetzner CAX21 (4 vCPU ARM, 8GB), Ubuntu 24.04. Resize to CAX31 only on measured memory pressure. |
| Ingress / TLS | Traefik (bundled with k3s) + cert-manager Let's Encrypt, host `cartogra.dev` |
| Secrets | Sealed Secrets; encrypted `SealedSecret`s committed under `infra/k8s/sealed/` |
| Namespaces | `cartogra` (product), `acme-system` (Acme release: reader ServiceAccount, bootstrap Job), `acme-fintech` (21 fake services, tenant-labelled), `ci-deploy` (CI identity) |
| CI access | `deployer` ServiceAccount (`infra/k8s/cluster-setup.yaml`), admin of the three app namespaces only |
| Alerts | `alerting` pod (Prometheus + Alertmanager), email through Resend |
| Backups | `postgres-backup` CronJob, nightly `pg_dump -Fc` to Hetzner Object Storage |

**What has been run.** On a local k3d cluster: `cluster-setup.yaml`, both add-on charts, the CI identity installing
and upgrading both charts (with plain Secrets instead of sealed ones), its denials, the database restore, and
restoring the Sealed Secrets key. **Never run yet:** sections 1 and 2, `kubeseal`, Let's Encrypt, GHCR, `deploy.yml`,
S3 and alert email delivery. Expect to correct this runbook on the first real build.

**Wizard.** `bash infra/k8s/demo-host-wizard.sh` walks sections 1–8 interactively: it opens each console page, saves
values to `~/.config/cartogra-demo/demo.env` (outside the repo), and skips finished stages on a re-run. It retries
CAX21 creation across fsn1, nbg1 and hel1 every 5 minutes while the type is out of stock.

## 1. Before you start

### Tools

`hcloud`, `kubectl`, `helm` >= 3.14, [`kubeseal`](https://github.com/bitnami/sealed-secrets#kubeseal), `gh`, `aws`,
`git`, `jq`, `openssl`, `dig`, Node >= 24 + pnpm.

### Code on `main`

`images.yml` and `deploy.yml` only run from the default branch, so this code must be merged first.

```bash
gh workflow list --repo allanweber/cartogra | grep -E 'Images|Deploy demo'   # both listed
```

### Accounts and values

Do these first; section 4 seals the results. Keep every value in your password manager.

**Hetzner Cloud.** Console → your project → Security → API tokens → generate a read/write token, then:

```bash
hcloud context create cartogra
hcloud ssh-key create --name cartogra-demo --public-key-from-file ~/.ssh/id_ed25519.pub
```

**Hetzner Object Storage.** Console → Security → S3 credentials → generate; note the access and secret keys. Then
create the bucket with a 7-day expiry. Bucket names are global: if this one is taken, change `backup.s3.bucket` in
`values-demo.yaml` and section 10.

```bash
export AWS_ACCESS_KEY_ID=<access key> AWS_SECRET_ACCESS_KEY=<secret key> AWS_DEFAULT_REGION=fsn1
S3=https://fsn1.your-objectstorage.com
aws s3 mb s3://cartogra-demo-backups --endpoint-url $S3
aws s3api put-bucket-lifecycle-configuration --bucket cartogra-demo-backups --endpoint-url $S3 \
  --lifecycle-configuration '{"Rules":[{"ID":"expire","Status":"Enabled","Filter":{"Prefix":"postgres/"},"Expiration":{"Days":7}}]}'
```

**Resend.** [resend.com/domains](https://resend.com/domains) → add `cartogra.dev`, create the DNS records it lists,
wait for **Verified**. Then API Keys → create one with **Sending access**, limited to that domain. This is
`RESEND_API_KEY`; alerts come from `alerts@cartogra.dev`.

**GitHub mirror.** The demo scans a public GitHub copy of Acme's 21 repos (the seed keeps Azure DevOps for local
runs).

1. Create a public organization at [github.com/organizations/new](https://github.com/organizations/new).
2. Push the repos:

   ```bash
   gh auth login && gh auth setup-git
   cd seed/acme-fintech && pnpm install
   ACME_GITHUB_ORG=<org> pnpm acme repos --provider github
   cd ../..
   ```

3. Create a [fine-grained token](https://github.com/settings/personal-access-tokens/new): resource owner = that
   org, repository access **Public repositories (read-only)**, no other permissions. This is `ACME_GITHUB_TOKEN`.
   Never reuse a personal token here: the demo tenant is publicly readable.

The Acme tenant holds only fabricated data (ADR-0019 §9): the bootstrap admin is `admin@acme-fintech.invalid`
(reserved TLD, no mail delivered; recover a lost password by re-sealing `BOOTSTRAP_ADMIN_PASSWORD` and resetting the
hash in the database), and nobody is invited into the tenant.

## 2. VM and k3s

```bash
hcloud firewall create --name cartogra-demo
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 22   --source-ips <your-ip>/32
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 80   --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 443  --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cartogra-demo --direction in --protocol tcp --port 6443 --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud server create --name cartogra-demo --type cax21 --image ubuntu-24.04 --location fsn1 \
  --ssh-key cartogra-demo --firewall cartogra-demo
IP=$(hcloud server ip cartogra-demo); echo "$IP"
```

6443 is open to the world because GitHub-hosted runners have no fixed IPs; the API server still requires
credentials. Close it (self-hosted runner or a tunnel) if that ever matters.

At your DNS provider, point the `cartogra.dev` A record at `$IP`. Let's Encrypt needs it, so wait until this prints
the IP:

```bash
dig +short cartogra.dev
```

Install k3s and fetch the admin kubeconfig. It stays on your machine, never in CI:

```bash
ssh -o StrictHostKeyChecking=accept-new root@$IP \
  "curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC='--tls-san $IP --tls-san cartogra.dev' sh -"
mkdir -p ~/.kube
scp root@$IP:/etc/rancher/k3s/k3s.yaml ~/.kube/cartogra-demo
sed -i "s/127.0.0.1/$IP/" ~/.kube/cartogra-demo && chmod 600 ~/.kube/cartogra-demo
export KUBECONFIG=~/.kube/cartogra-demo
kubectl get nodes   # Ready
```

k3s ships Traefik (ingress class `traefik`) and the `local-path` storage class the StatefulSets use. It also enforces
NetworkPolicy (kube-router), which keeps the backends' trusted tenant headers away from arbitrary pods (allow lists in
the chart README's Exposure section).
Don't install it with `--disable-network-policy`, and don't swap in a CNI that doesn't enforce NetworkPolicy: the
policies would be ignored without any error.

## 3. Cluster add-ons

Namespaces and the CI identity. Pod Security `baseline` is enforced on the app namespaces, so nothing there can run
privileged or mount the host:

```bash
kubectl apply -f infra/k8s/cluster-setup.yaml
```

**Rebuilding only:** restore the Sealed Secrets key now, *before* installing the controller, so it adopts the key
instead of generating a new one:

```bash
kubectl apply -f sealed-secrets-key.yaml
```

Add-ons:

```bash
helm repo add jetstack https://charts.jetstack.io
helm repo add sealed-secrets https://bitnami.github.io/sealed-secrets
helm repo update
helm upgrade --install cert-manager jetstack/cert-manager -n cert-manager --create-namespace \
  --set crds.enabled=true --wait --timeout 5m
helm upgrade --install sealed-secrets sealed-secrets/sealed-secrets -n kube-system \
  --set fullnameOverride=sealed-secrets-controller --wait --timeout 5m
```

ClusterIssuer (put your own email in):

```bash
kubectl apply -f - <<'YAML'
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: <you>@<domain>
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
      - http01:
          ingress:
            ingressClassName: traefik
YAML
```

**First build only:** back up the Sealed Secrets private key, put it in your password manager and delete the file.
It decrypts every committed `SealedSecret`; never commit it.

```bash
kubectl -n kube-system get secret -l sealedsecrets.bitnami.com/sealed-secrets-key -o yaml > sealed-secrets-key.yaml
```

## 4. Secrets

The charts create no Secrets; each is mounted as files. `kubeseal` encrypts them for this cluster's controller, and
only the encrypted files are committed.

| Namespace | Secret | Keys |
|---|---|---|
| `cartogra` | `gateway-secrets` | `DB_USERNAME` `DB_PASSWORD` `JWT_SECRET` `RESEND_API_KEY` `GOOGLE_CLIENT_SECRET` `GITHUB_CLIENT_SECRET` `BOOTSTRAP_ADMIN_PASSWORD` |
| `cartogra` | `registry-secrets`, `topology-secrets` | `DB_USERNAME` `DB_PASSWORD` |
| `cartogra` | `ingestion-secrets` | `DB_USERNAME` `DB_PASSWORD` `CLUSTER_CREDENTIALS_KEY` |
| `cartogra` | `postgres-secrets` | `DB_USERNAME` `DB_PASSWORD` (same values) |
| `cartogra` | `backup-secrets` | `S3_ACCESS_KEY` `S3_SECRET_KEY` |
| `acme-system` | `acme-bootstrap` | `ACME_PASSWORD` (= `BOOTSTRAP_ADMIN_PASSWORD`), `ACME_GITHUB_TOKEN` |

Generate the values. Save all of them in your password manager; only the ciphertext is recoverable from git.
`CLUSTER_CREDENTIALS_KEY` must stay the same for as long as the database lives, because it decrypts stored credentials.

```bash
DB_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -base64 48)
ADMIN_PASSWORD=$(openssl rand -hex 16)
CLUSTER_CREDENTIALS_KEY=$(openssl rand -base64 32)
read -rsp 'RESEND_API_KEY: ' RESEND_API_KEY; echo
read -rsp 'ACME_GITHUB_TOKEN: ' ACME_GITHUB_TOKEN; echo
read -rsp 'S3_ACCESS_KEY: ' S3_ACCESS_KEY; echo
read -rsp 'S3_SECRET_KEY: ' S3_SECRET_KEY; echo
```

Seal and apply. OAuth login is off in the demo, so its two secrets only need a placeholder:

```bash
mkdir -p infra/k8s/sealed
seal() {
  ns=$1; name=$2; shift 2
  kubectl -n "$ns" create secret generic "$name" --dry-run=client -o yaml "$@" \
    | kubeseal --format yaml > "infra/k8s/sealed/$name.yaml"
}
DB=(--from-literal=DB_USERNAME=cartogra --from-literal=DB_PASSWORD="$DB_PASSWORD")
seal cartogra gateway-secrets "${DB[@]}" \
  --from-literal=JWT_SECRET="$JWT_SECRET" \
  --from-literal=RESEND_API_KEY="$RESEND_API_KEY" \
  --from-literal=GOOGLE_CLIENT_SECRET=unset \
  --from-literal=GITHUB_CLIENT_SECRET=unset \
  --from-literal=BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD"
seal cartogra registry-secrets "${DB[@]}"
seal cartogra topology-secrets "${DB[@]}"
seal cartogra postgres-secrets "${DB[@]}"
seal cartogra ingestion-secrets "${DB[@]}" --from-literal=CLUSTER_CREDENTIALS_KEY="$CLUSTER_CREDENTIALS_KEY"
seal cartogra backup-secrets --from-literal=S3_ACCESS_KEY="$S3_ACCESS_KEY" --from-literal=S3_SECRET_KEY="$S3_SECRET_KEY"
seal acme-system acme-bootstrap --from-literal=ACME_PASSWORD="$ADMIN_PASSWORD" --from-literal=ACME_GITHUB_TOKEN="$ACME_GITHUB_TOKEN"

kubectl apply -f infra/k8s/sealed/
kubectl -n cartogra get secrets      # 6 secrets, created by the controller
kubectl -n acme-system get secrets   # acme-bootstrap
```

Commit the encrypted files. `main` is protected, so through a PR:

```bash
git switch -c chore/demo-sealed-secrets
git add infra/k8s/sealed/ && git commit -m "chore(infra): sealed secrets for the demo host"
git push -u origin HEAD && gh pr create --fill && gh pr merge --squash --auto
```

Rotating one value: seal it into the existing file, commit, apply, restart what reads it.

```bash
printf '%s' '<new value>' | kubectl -n cartogra create secret generic gateway-secrets --dry-run=client -o yaml \
    --from-file=JWT_SECRET=/dev/stdin \
  | kubeseal --format yaml --merge-into infra/k8s/sealed/gateway-secrets.yaml
kubectl apply -f infra/k8s/sealed/gateway-secrets.yaml
kubectl -n cartogra rollout restart deploy/gateway
```

`RESEND_API_KEY` is also read by `deploy/alerting`. `BOOTSTRAP_ADMIN_PASSWORD` is only used when the admin is first
created; rotate it in the UI as well.

## 5. Images

`images.yml` pushes all six images on every push to `main`, tagged with the commit SHA. Find the latest:

```bash
SHA=$(gh run list --repo allanweber/cartogra --workflow images.yml --branch main --status success --limit 1 \
  --json headSha --jq '.[0].headSha'); echo "$SHA"
```

k3s pulls without credentials, so the packages must be public. Packages only appear after the first push, and
visibility can only be changed in the UI: for each of `gateway`, `registry`, `topology`, `ingestion`, `frontend` and
`acme`, open `https://github.com/users/allanweber/packages/container/cartogra-<image>/settings` → **Change
visibility** → Public. Check without credentials:

```bash
docker logout ghcr.io
docker manifest inspect ghcr.io/allanweber/cartogra-gateway:$SHA > /dev/null && echo public
```

## 6. CI access

`deploy.yml` uses a kubeconfig for the `deployer` ServiceAccount, not the k3s admin one. If this GitHub secret leaks,
the holder controls the app namespaces and can read their Secrets, but cannot reach `kube-system` (the Sealed Secrets
key), other namespaces or the node.

```bash
TOKEN=$(kubectl -n ci-deploy get secret deployer-token -o jsonpath='{.data.token}' | base64 -d)
kubectl -n ci-deploy get secret deployer-token -o jsonpath='{.data.ca\.crt}' | base64 -d > /tmp/demo-ca.crt
CI=$(mktemp)
kubectl --kubeconfig "$CI" config set-cluster demo --server="https://$IP:6443" \
  --certificate-authority=/tmp/demo-ca.crt --embed-certs=true
kubectl --kubeconfig "$CI" config set-credentials deployer --token="$TOKEN"
kubectl --kubeconfig "$CI" config set-context demo --cluster=demo --user=deployer --namespace=cartogra
kubectl --kubeconfig "$CI" config use-context demo
kubectl --kubeconfig "$CI" auth can-i list pods -n cartogra        # yes
kubectl --kubeconfig "$CI" auth can-i get secrets -n kube-system   # no

gh api -X PUT repos/allanweber/cartogra/environments/demo --input - <<JSON
{"reviewers":[{"type":"User","id":$(gh api user --jq .id)}]}
JSON
gh secret set KUBECONFIG --repo allanweber/cartogra --env demo < "$CI"
gh variable set ACME_GITHUB_ORG --repo allanweber/cartogra --env demo --body <org>
gh variable set ALERT_EMAIL --repo allanweber/cartogra --env demo --body <who receives alerts>
rm -f "$CI" /tmp/demo-ca.crt
```

To rotate the token: `kubectl -n ci-deploy delete secret deployer-token`, re-apply `cluster-setup.yaml`, repeat the
commands above.

## 7. Install / upgrade

To test chart changes before deploying, run them on a local cluster first: see [Test on a local cluster](../../infra/helm/README.md).

Through CI, then approve the run as the `demo` reviewer:

```bash
gh workflow run deploy.yml --repo allanweber/cartogra --ref main -f sha=$SHA
gh run watch --repo allanweber/cartogra "$(gh run list --repo allanweber/cartogra --workflow deploy.yml --limit 1 --json databaseId --jq '.[0].databaseId')"
```

By hand, the same commands:

```bash
helm upgrade --install cartogra infra/helm/cartogra -n cartogra \
  -f infra/helm/cartogra/values-demo.yaml --set global.imageTag=$SHA \
  --set alerting.email.to=<alert email> --wait --timeout 15m
helm upgrade --install acme infra/helm/acme -n acme-system \
  --set image.tag=$SHA --set bootstrap.githubOrg=<org> --wait --timeout 20m
```

What happens on a fresh install:

1. Registry runs Flyway; the gateway's tenant bootstrap (`BOOTSTRAP_*` in `values-demo.yaml`) creates the Acme
   tenant (fixed UUID, business plan) and its verified admin once the tables exist (`tenant_bootstrap.created`
   in the gateway log). Registry, topology and ingestion may restart once while Postgres comes up.
2. The `acme` release adopts the `acme-fintech` namespace and creates the 21 fake services and the read-only
   `cartogra-reader` ServiceAccount with its token.
3. The `acme-bootstrap` hook Job logs in, registers this cluster (`https://kubernetes.default.svc` + reader
   token), connects the GitHub mirror and triggers a sync, then runs `acme load` (teams, owners, tiers, declared
   edges) once discovery has produced all 21 services. It re-runs idempotently on every upgrade.

## 8. Verify

```bash
kubectl -n cartogra get pods                         # all Ready (alerting is 2/2)
kubectl -n acme-fintech get pods --no-headers | grep -c Running   # 21
kubectl -n acme-system get jobs                      # empty: the bootstrap Job is deleted on success
kubectl -n cartogra get certificate cartogra-tls     # READY True, within a few minutes
curl -s https://cartogra.dev/api/auth/features | jq .data   # signupEnabled false, guestEnabled true
```

NetworkPolicy (expect `gateway 200`, then `ingestion blocked` after a 3s timeout):

```bash
kubectl -n cartogra run np-check --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  sh -c 'curl -sm3 -o /dev/null -w "gateway %{http_code}\n" http://gateway:8080/actuator/health/live;
         curl -sm3 -o /dev/null -w "ingestion %{http_code}\n" http://ingestion:8085/ || echo "ingestion blocked"'
```

In a browser, `https://cartogra.dev/demo` lands on the dashboard, the graph shows Acme with observed edges
within about a minute, and write buttons are disabled.

Alert email (once per host; the only check that delivery through Resend works):

```bash
kubectl -n cartogra port-forward deploy/alerting 9093 &
curl -s -XPOST localhost:9093/api/v2/alerts -H 'Content-Type: application/json' \
  -d '[{"labels":{"alertname":"DeliveryTest"},"annotations":{"summary":"Alert email test"}}]'
kill %1
```

The email arrives within a minute. If not: `kubectl -n cartogra logs deploy/alerting -c alertmanager`.

Backup (once per host): run section 10's manual job and confirm the file with `aws s3 ls`.

Uptime check: in any external monitor (for example UptimeRobot's free tier), add an HTTPS keyword monitor on
`https://cartogra.dev/api/auth/features`, keyword `guestEnabled`, 5-minute interval, alerting to your email. Actuator
endpoints are not routed through the ingress. Use the keyword: while the gateway is down, `/api` paths can fall through
to the frontend and return HTML with status 200.

## 9. Rollback

```bash
helm -n cartogra history cartogra
helm -n cartogra rollback cartogra <revision> --wait
```

Or re-run **Deploy demo** with an older SHA. Flyway migrations are forward-only and additive, so older pods
run against a newer schema; never roll the schema back by hand.

## 10. Backups and restore

`postgres-backup` (`values-demo.yaml` → `backup`) dumps the database nightly at 02:00 UTC to
`s3://cartogra-demo-backups/postgres/cartogra-<timestamp>.dump`. The bucket's lifecycle rule (section 1) expires files
after 7 days. Run one now:

```bash
kubectl -n cartogra create job --from=cronjob/postgres-backup backup-manual
kubectl -n cartogra logs -f job/backup-manual -c upload
aws s3 ls s3://cartogra-demo-backups/postgres/ --endpoint-url https://fsn1.your-objectstorage.com
kubectl -n cartogra delete job backup-manual
```

Restore (needs the `AWS_*` variables from section 1). Scaling down also stops the alerting pod until the last step:

```bash
kubectl -n cartogra scale deploy --all --replicas=0
aws s3 cp s3://cartogra-demo-backups/postgres/<file>.dump restore.dump --endpoint-url https://fsn1.your-objectstorage.com
kubectl -n cartogra cp restore.dump postgres-0:/tmp/restore.dump
kubectl -n cartogra exec postgres-0 -- sh -c 'pg_restore --clean --if-exists -U "$(cat /etc/cartogra/secrets/DB_USERNAME)" -d cartogra /tmp/restore.dump'
kubectl -n cartogra exec postgres-0 -- rm /tmp/restore.dump
rm restore.dump
kubectl -n cartogra scale deploy --all --replicas=1
```

The restored database only works with the same `CLUSTER_CREDENTIALS_KEY` it was written with.

## 11. Rebuild from scratch

You need `sealed-secrets-key.yaml` from your password manager. The accounts in section 1 still exist, so skip them.

1. Section 2: new VM and k3s. Update the DNS record if the IP changed.
2. Section 3, including the "rebuilding only" key restore. Skip the first-build backup.
3. `kubectl apply -f infra/k8s/sealed/` instead of section 4. Check the Secrets exist.
4. Section 6: the cluster and its token are new, so the `KUBECONFIG` GitHub secret must be replaced. The variables are
   unchanged.
5. Section 7, then section 8.
6. Restore data (section 10) only if the database must survive; otherwise the bootstrap recreates Acme.

If the key is lost: run section 4 again with new values (restore then needs the old `CLUSTER_CREDENTIALS_KEY`, so
keep that one).

---

## 12. SCM provider credential setup

SCM provider credentials are stored in the `ingestion.scm_connections` table as JSONB (`config` column). They are **never** stored in environment variables on the ingestion service. SCM connection management lives in the **ingestion** service (gateway route `/api/v1/ingestion/scm-connections`), not registry.

### GitHub — Personal Access Token

1. Create a PAT at **GitHub → Settings → Developer settings → Personal access tokens** with scopes: `repo` (read), `read:org`.
2. Insert a connection record via the Ingestion API (gateway-authenticated):

```http
POST /api/v1/ingestion/scm-connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "provider": "github",
  "config": "{\"token\":\"<PAT value>\",\"org\":\"your-github-org\"}",
  "syncScheduler": true,
  "pollIntervalMinutes": 15,
  "webhookEnabled": true
}
```

**Security:** Ingestion encrypts the token with AES-GCM using `CLUSTER_CREDENTIALS_KEY` before storing it, and never logs it.

### Azure DevOps — Personal Access Token

1. Create a PAT at **Azure DevOps → User Settings → Personal Access Tokens** with scopes: `Code (Read)`, `Graph (Read)`.
2. Insert a connection record:

```http
POST /api/v1/ingestion/scm-connections
Authorization: Bearer <token>
X-Tenant-Id: <tenantId>
Content-Type: application/json

{
  "provider": "azuredevops",
  "config": "{\"pat\":\"<PAT value>\",\"organization\":\"your-ado-org\"}"
}
```

### Triggering a manual sync (smoke test)

```bash
curl -s -X POST https://cartogra.dev/api/v1/ingestion/scm-connections/<connectionId>/sync -H "Authorization: Bearer $TOKEN" | jq .
```

Monitor the `sync_jobs` table to observe the PENDING → RUNNING → COMPLETED transition:

```sql
SELECT id, status, repositories_synced, error_message, updated_at
FROM ingestion.sync_jobs
ORDER BY created_at DESC
LIMIT 5;
```

The completed sync writes its outcome back onto the connection (`last_sync_status`, `last_sync_at`):

```sql
SELECT id, provider, last_sync_status, last_sync_at, next_sync_at
FROM ingestion.scm_connections
ORDER BY updated_at DESC
LIMIT 5;
```

## 13. Scheduled sync

When `scm_connections.sync_scheduler = true`, the ingestion `SyncScheduler` polls for due connections and publishes a `sync.command` for each.

- **Cadence:** the scheduler tick runs every `INGESTION_SYNC_POLL_INTERVAL` (ISO-8601 duration, default `PT15M`). Per-connection cadence is `poll_interval_minutes`, tracked via `next_sync_at`.
- **Multi-instance safety:** each connection is published inside a `REQUIRES_NEW` transaction guarded by a PostgreSQL transaction-scoped advisory lock (`pg_try_advisory_xact_lock`). With N ingestion replicas, only the replica that wins the lock publishes — no duplicate commands.
- **Delivery semantics:** at-least-once. `next_sync_at` is advanced outside the lock transaction, so a crash between publish and timestamp-advance results in a retry on the next tick (deduplicated downstream by the RUNNING-job guard in `SyncExecutionService`).

Inspect due connections:

```sql
SELECT id, provider, poll_interval_minutes, next_sync_at
FROM ingestion.scm_connections
WHERE sync_scheduler = true AND deleted_at IS NULL
  AND (next_sync_at IS NULL OR next_sync_at <= now());
```

To pause scheduling for a connection: `PUT /api/v1/ingestion/scm-connections/{id}` with `{"syncScheduler": false}` (clears `next_sync_at`).

## 14. Webhook setup

Real-time sync is driven by SCM webhooks. The gateway exposes the receiver publicly at `/api/v1/ingestion/webhooks/{providerType}/{connectionId}` (no JWT). Per-provider authentication is verified against the secret stored in `ingestion.scm_webhooks.webhook_secret`.

**Register a webhook secret** (one row per connection in `scm_webhooks`) and set `scm_connections.webhook_enabled = true`.

### GitHub

1. Repo/org **Settings → Webhooks → Add webhook**.
2. **Payload URL:** `https://<gateway-host>/api/v1/ingestion/webhooks/github/<connectionId>`
3. **Content type:** `application/json`
4. **Secret:** the value stored in `scm_webhooks.webhook_secret`. Ingestion verifies `X-Hub-Signature-256` (HMAC-SHA256 over the raw body).
5. **Events:** at least `push`. (`ping` is accepted with `202` but does not trigger a sync.)

### Azure DevOps

1. **Project Settings → Service hooks → Create subscription → Web Hooks**.
2. **Trigger:** *Code pushed* (`git.push`).
3. **URL:** `https://<gateway-host>/api/v1/ingestion/webhooks/azuredevops/<connectionId>`
4. **Basic auth:** set username/password; store the same `user:password` string in `scm_webhooks.webhook_secret`. Ingestion verifies the `Authorization: Basic` header.

### Responses

| Outcome | Status |
|---------|--------|
| Valid signature, actionable event | `202` + `sync.command` published |
| Valid signature, non-actionable (e.g. GitHub `ping`) | `202`, no publish |
| Invalid signature / Basic auth | `401` (`WEBHOOK_SIGNATURE_INVALID`) |
| No webhook registration / unknown provider | `404` (`WEBHOOK_CONNECTION_NOT_FOUND`) |

Webhook responses are **not** wrapped in the standard response envelope.

## 15. Observed edges / OTel intake

Observed dependency edges ([ADR-0029](../adr/0029-observed-edges-from-otel-spans.md)) need an OTel collector that exports spans to Kafka. The deployable compose and the Helm chart (`infra/helm/cartogra`, `collector` subchart) share one config. Local procedure: [`local-development.md` → Testing observed edges](local-development.md#testing-observed-edges-otel-spans).

### Current state per environment

| Environment | Collector | Spans → Kafka | Observed edges |
|-------------|-----------|---------------|----------------|
| Local (`docker-compose.dev.yml`) | yes, single tenant via `CARTOGRA_TENANT_ID` | yes | works |
| `docker-compose.yml` (deployable) | yes, `infra/helm/cartogra/charts/collector/files/otel-collector.yml` (spans plus a metrics port, no Tempo/Loki), OTLP/HTTP on `127.0.0.1:4318` | yes | works, single tenant via `CARTOGRA_TENANT_ID` |
| Kubernetes (Helm `collector` subchart) | yes, same config file; ClusterIP only, never behind ingress | yes | works, single tenant via `collector.env.CARTOGRA_TENANT_ID` |

Topology is safe to deploy anyway: the `OtelSpanWorker` simply idles when `cartogra.observability.spans` receives nothing.

### Deploying the Topology change

- **Migration `V011__observed_edges.sql`** is additive and backward-compatible (nullable `dependencies.last_seen_at`, new tables `pending_span_links` and `unresolved_span_services` with RLS, one index). Old pods keep working during the rolling update; follow section 3, no expand/contract needed.
- **Kafka access:** Topology's consumer group `topology-consumer` needs read on `cartogra.observability.spans`, plus write on `cartogra.observability.spans.dlq` for quarantined records. The dev broker auto-creates both topics; **create them explicitly** in any environment where auto-create is off.
- **Rollback:** rolling Topology back is safe. The new tables and column are ignored by old code. Do not drop them on rollback; the `OtelSpanWorker` simply stops.
- **Metrics to watch after enabling:** `cartogra_spans_quarantined_total` (must stay ~0 once the tenant stamp is correct), `cartogra_spans_edges_total`, consumer lag on `cartogra.observability.spans`.

### Span feed security

The tenant attribute is unauthenticated until roadmap 5.9: anyone who can reach the collector (4317/4318) or the topic can attribute spans to **any tenant UUID that has live nodes**. Keep those ports off the ingress. The collector serves exactly one tenant (`collector.env.CARTOGRA_TENANT_ID`), so this only suits a single-tenant environment. Check it with `kubectl -n cartogra logs deploy/collector`.

### Verification

1. Collector logs show no Kafka exporter errors.
2. Message count on `cartogra.observability.spans` grows (Kafka UI or `kafka-console-consumer`).
3. `cartogra_spans_quarantined_total` stays flat; if `reason=tenant_malformed` rises, the tenant env var is missing or invalid.
4. After real CLIENT traffic between catalogued services, `select count(*) from topology.dependencies where dependency_type='observed'` increases and the Observed graph shows edges (allow ~10 s).

### Planned

| Roadmap | Change |
|---------|--------|
| 5.9 | Authenticated gateway intake `POST /v1/otlp/traces` (`X-Cartogra-Api-Key`, scope `spans:write`); the gateway overwrites `cartogra.tenant.id` from the key and the collector stops accepting external traffic. `CARTOGRA_TENANT_ID` is dropped. Settings → Connections → OpenTelemetry card for keys, endpoint and status. |
