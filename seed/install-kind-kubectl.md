# Kubernetes with kind on CachyOS

## 1. Install

```bash
sudo pacman -S kubectl

curl -Lo /tmp/kind https://kind.sigs.k8s.io/dl/latest/kind-linux-amd64
sudo install /tmp/kind /usr/local/bin/kind
rm /tmp/kind
```

---

## 2. Create Cluster

```bash
kind create cluster --name cartogra
```

Verify:

```bash
kubectl get nodes
```

---

## 3. Metrics Server

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

Patch to allow insecure kubelet TLS (required on kind):

```bash
kubectl patch deployment metrics-server -n kube-system \
  --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

Verify:

```bash
kubectl top nodes
```

---

## 4. Dashboard

```bash
kubectl apply -f https://raw.githubusercontent.com/kubernetes/dashboard/v2.7.0/aio/deploy/recommended.yaml
```

Create admin user:

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: admin-user
  namespace: kubernetes-dashboard
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: admin-user
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
- kind: ServiceAccount
  name: admin-user
  namespace: kubernetes-dashboard
EOF
```

Get token:

```bash
kubectl -n kubernetes-dashboard create token admin-user
```

Access:

```bash
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard 8443:443
```

Open `https://localhost:8443`, paste the token.

---

## Common Commands

```bash
kubectl top nodes
kind get clusters
kind delete cluster --name cartogra
kind create cluster --name cartogra
kubectl config use-context kind-cartogra
kubectl get pods -A
kubectl get events -A
kubectl describe pod <pod-name>

kubectl -n kubernetes-dashboard create token admin-user
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard 8443:443

kind get kubeconfig --name cartogra > /tmp/cartogra-kubeconfig.yaml
```
