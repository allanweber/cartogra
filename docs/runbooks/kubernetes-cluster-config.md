# Runbook: Getting Kubernetes Cluster Configuration for Cartogra

Cartogra's "Add cluster" dialog (`Settings → Connections → Kubernetes`) accepts credentials
via two auth methods:

| Auth method | Fields | Best for |
|---|---|---|
| **Kubeconfig** | Paste/upload a kubeconfig YAML; active context is used | Local/dev clusters |
| **Service account** | API server URL, CA cert (PEM), service account token, skip-TLS-verify | Production clusters |

Cartogra only needs **read** access to `namespaces`, `services`, and `endpoints`
(list/watch/get) — it discovers and health-checks Services, it never writes to the cluster.

---

## Local (kind)

Assumes a `kind` cluster set up per `seed/install-kind-kubectl.md`.

### Option A — Kubeconfig (fastest for local)

```bash
kind get kubeconfig --name cartogra > /tmp/cartogra-kubeconfig.yaml
```

Paste the contents of `/tmp/cartogra-kubeconfig.yaml` into the dialog, or use **Upload file**.
The active context (`kind-cartogra`) is used automatically.

Discard the temp file after pasting:

```bash
rm /tmp/cartogra-kubeconfig.yaml
```

### Option B — Service account (to mirror prod behavior locally)

Create a scoped service account + token, same as prod (see below), then use:

- **API server URL**: `kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'`
- **CA certificate**: extract from kubeconfig (see prod steps) — or check **Skip TLS verify** for kind, since its CA is self-signed and short-lived per cluster recreate
- **Service account token**: from `kubectl -n cartogra-system create token cartogra-reader --duration=87600h`

---

## Production

Never paste a personal/admin kubeconfig or a long-lived `cluster-admin` token into Cartogra.
Create a dedicated, least-privilege service account instead.

### 1. Create the namespace, service account, and RBAC

```bash
kubectl create namespace cartogra-system

kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cartogra-reader
  namespace: cartogra-system
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: cartogra-reader
rules:
  - apiGroups: [""]
    resources: ["namespaces", "services", "endpoints"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: cartogra-reader
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cartogra-reader
subjects:
  - kind: ServiceAccount
    name: cartogra-reader
    namespace: cartogra-system
EOF
```

### 2. Mint a token

Kubernetes 1.24+ no longer auto-generates long-lived Secret tokens for service accounts.
Request a bound token explicitly. Cartogra's ingestion worker refreshes on `ClusterStatus`
reconnect, but tokens still expire — use a long duration and rotate on a schedule:

```bash
kubectl -n cartogra-system create token cartogra-reader --duration=87600h   # ~10 years
```

Copy the printed JWT — this is the **Service account token** field. It is shown once.

### 3. Get the API server URL and CA certificate

```bash
kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.server}'
echo

kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}' \
  | base64 -d
```

- **API server URL** → output of the first command (e.g. `https://<eks-endpoint>:443`)
- **CA certificate (PEM)** → output of the second command, including
  `-----BEGIN CERTIFICATE-----` / `-----END CERTIFICATE-----`

Leave **Skip TLS verify** off in production — the CA cert above is sufficient for the client
to trust the API server.

For managed clusters, the values are also retrievable without `kubectl config`:

```bash
# EKS
aws eks describe-cluster --name cartogra-prod --region us-east-1 \
  --query 'cluster.{endpoint:endpoint,ca:certificateAuthority.data}'

# GKE
gcloud container clusters describe cartogra-prod --region us-east1 \
  --format='value(endpoint,masterAuth.clusterCaCertificate)'

# AKS
az aks show -g cartogra-rg -n cartogra-prod \
  --query 'fqdn'
az aks show -g cartogra-rg -n cartogra-prod \
  --query 'agentPoolProfiles[0]' # CA is easiest to pull via `az aks get-credentials` + kubeconfig steps above
```

The `certificateAuthority.data` / `clusterCaCertificate` values are base64-encoded — decode
with `| base64 -d` before pasting into the CA certificate field.

### 4. Fill in the dialog

1. **Add cluster** → **Auth method** → `Service account`
2. **API server URL** → from step 3
3. **CA certificate (PEM)** → from step 3
4. **Service account token** → from step 2
5. **Skip TLS verify** → off
6. Save — Cartogra transitions the cluster to `CONNECTING`, then `CONNECTED` once the
   worker successfully lists namespaces

### 5. Rotate the token

Bound tokens expire at the configured `--duration`. Before expiry, mint a new token
(step 2) and update the cluster via **Edit cluster** → paste the new token. The API server
URL and CA cert fields can be left blank on edit to keep existing values.

### 6. Label namespaces for discovery

Cartogra only discovers namespaces bearing the tenant label:

```bash
kubectl label namespace <target-namespace> cartogra.io/tenant-id=<your-tenant-uuid>
```

Without this label, `cartogra-reader` can technically list the namespace but Cartogra will
not ingest Services from it.

---

## Revoking access

```bash
kubectl delete clusterrolebinding cartogra-reader
kubectl delete clusterrole cartogra-reader
kubectl delete serviceaccount cartogra-reader -n cartogra-system
```

Removing the binding/role immediately invalidates the token's permissions even though the
JWT itself remains valid until its `exp` claim.
