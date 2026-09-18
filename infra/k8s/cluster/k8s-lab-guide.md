# Kubernetes lab: from empty cloud VMs to a cluster you understand

Companion to the two infra files, either of which creates three empty Ubuntu VMs
on Hetzner Cloud and nothing else:

- `k8s-lab-infra-hcloud.sh` — a shell script using the `hcloud` CLI. Fastest to run.
- `k8s-lab-infra.tf` — the same infrastructure as Terraform, if you'd rather manage it declaratively.

Both provision three `CX33`-class nodes (4 vCPU / 8 GB / 80 GB each) on a private
network, which is enough to run the cluster plus a Prometheus/Grafana/EFK
observability stack (Part 10). Everything below you type yourself, because the
point is the muscle memory, not the cluster.

> Earlier drafts of this lab targeted AWS EC2 via CloudFormation. That approach is
> retained in `k8s-lab-infra.yaml` if you ever want it, but the guide now assumes
> Hetzner: real KVM VMs, a fraction of the cost, and no cloud-provider lock-in on
> the learning.

Work through it in order. Part 2 builds the real cluster by hand; Parts 4–8 run on
that same cluster once it's up.

| Part | What | Where | Time |
|---|---|---|---|
| 1 | Shell setup: autocomplete, aliases, vim | anywhere | 10 min |
| 2 | Real cluster with `kubeadm` | Hetzner | 60 min |
| 3 | Map the control plane and the workers | Hetzner | 45 min |
| 4 | First app: Pod → Deployment → Service | Hetzner | 30 min |
| 5 | `kubectl` drills | Hetzner | 45 min |
| 6 | Namespaces, ResourceQuotas, LimitRanges | Hetzner | 30 min |
| 7 | ConfigMaps and Secrets | Hetzner | 30 min |
| 8 | RBAC | Hetzner | 45 min |
| 10 | Observability: Prometheus, Grafana, EFK | Hetzner | 60 min |
| 9 | Teardown | — | 5 min |

Terms used throughout, spelled out once:

- **CNI** — Container Network Interface. The plugin that gives every pod an IP address and makes pod-to-pod traffic work. Kubernetes ships without one; a fresh cluster is broken until you install it.
- **CRI** — Container Runtime Interface. The API the kubelet uses to talk to the thing that actually runs containers (containerd, here).
- **RBAC** — Role-Based Access Control. Kubernetes' permission system.
- **SA** — ServiceAccount. An identity for processes running inside pods.
- **CKA / CKS** — Certified Kubernetes Administrator / Certified Kubernetes Security Specialist.

---

## Part 1 — Shell setup

Do this on every machine where you'll run `kubectl`, including the Hetzner control
plane. In the CKA and CKS exams you have roughly 7 minutes per task; typing
`kubectl` in full costs you real marks.

```bash
# bash
sudo apt-get install -y bash-completion   # Ubuntu; already present on most systems
echo 'source <(kubectl completion bash)' >> ~/.bashrc
echo 'alias k=kubectl' >> ~/.bashrc
echo 'complete -o default -F __start_kubectl k' >> ~/.bashrc

# generate-YAML-and-don't-apply, the single most useful shortcut
echo 'export do="--dry-run=client -o yaml"' >> ~/.bashrc

# delete immediately instead of waiting 30s for graceful termination
echo 'export now="--force --grace-period=0"' >> ~/.bashrc

source ~/.bashrc
```

zsh users: `source <(kubectl completion zsh)` and `compdef __start_kubectl k`.

Test that the pieces work:

```bash
k get no                     # alias + short name
k run tmp --image=nginx $do  # prints YAML, creates nothing
k get po --all-namespaces    # tab-complete resource names and pod names
```

Editing YAML in `vi` is unavoidable in the exam, and the default settings mangle
indentation. Fix it once:

```bash
cat >> ~/.vimrc <<'EOF'
set expandtab       " spaces, never tabs — YAML rejects tabs outright
set tabstop=2
set shiftwidth=2
set autoindent
set number
EOF
```

Two more habits worth building now:

```bash
# switch namespace without typing -n every time
kubectl config set-context --current --namespace=dev

# check installation
hcloud location list

# see which context and namespace you're actually in
kubectl config get-contexts
```

Losing marks by doing correct work in the wrong namespace is the single most
common exam failure.

---

## Part 2 — Real cluster with kubeadm

### 2.1 Deploy the infrastructure

First install and authenticate the CLI. Create an API token in the Hetzner Cloud
Console under Security → API Tokens (Read & Write), then:

```bash
# macOS: brew install hcloud   |   Linux: download from github.com/hetznercloud/cli
hcloud context create k8s-lab        # paste the token when prompted

# Create SSH key
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519_hetzner -C "hetzner"

# upload your SSH public key once, so the servers trust it
hcloud ssh-key create --name k8s-lab --public-key-from-file ~/.ssh/id_ed25519_hetzner.pub

# Config for multiple hosts, ~/.ssh/config:
Host hetzner-*
    IdentityFile ~/.ssh/id_ed25519_hetzner
    IdentitiesOnly yes
    User root

Host hetzner-1
    HostName <ip1>

Host hetzner-2
    HostName <ip2>

# Connect via
ssh hetzner-<x>
```

Then pick **one** of the two infra files. Both create the same thing: a private
network on `10.0.0.0/16`, a firewall, and three `CX33` nodes (4 vCPU / 8 GB / 80 GB)
with fixed private IPs `10.0.0.10` / `.11` / `.12`.

**Option A — hcloud script:**

```bash
export ADMIN_CIDR="$(curl -s -4 ifconfig.me)/32"   # your IP, for the firewall
export SSH_KEY_NAME=k8s-lab
bash k8s-lab-infra-hcloud.sh
```

**Option B — Terraform:**

```bash
terraform init
terraform apply \
  -var="admin_cidr=$(curl -s ifconfig.me)/32" \
  -var="ssh_key_name=my-key" \
  -var="hcloud_token=$(hcloud context active >/dev/null && cat ~/.config/hcloud/cli.toml | grep token | head -1 | cut -d'"' -f2)"
```

> On the server-type name: Hetzner's cost-optimized 4 vCPU / 8 GB / 80 GB plan is
> **CX33** as of 2026 (it was CX32 in the Gen3 line). If either tool errors with an
> unknown server type, run `hcloud server-type list | grep -i '8 GB'` and set the
> exact name — `cx32`/`cx33` in the script's `SERVER_TYPE`, or `server_type` in the
> `.tf` file.

Either way, list what you got and note the **public** IPs for SSH and the
**private** IPs (`10.0.0.1x`) for `kubeadm`:

```bash
hcloud server list -o columns=name,ipv4,private_net,status
```

Open three terminals, one SSH session per node. **Hetzner's Ubuntu image logs in as
`root`, not `ubuntu`:**

```bash
ssh root@<control-plane-public-ip>
ssh root@<worker-1-public-ip>
ssh root@<worker-2-public-ip>
```

Lost your local SSH key? The Hetzner Cloud Console has a built-in VNC console
(server → `>_` icon) that drops you at a root login without SSH.

### 2.2 OS preparation — run on ALL THREE nodes

Give each node a name you can read. `kubeadm` uses the hostname as the node name,
and it must be unique and resolvable:

```bash
sudo hostnamectl set-hostname control-plane   # or worker-1 / worker-2
exec bash                                     # refresh the prompt
```

Kubernetes refuses to start if swap is on, because the scheduler's memory
accounting assumes a pod evicted from memory is actually gone. Ubuntu on Hetzner
has no swap, but verify and make it permanent:

```bash
sudo swapoff -a
sudo sed -i '/ swap / s/^/#/' /etc/fstab
swapon --show      # empty output = correct
free -h            # Swap row should read 0B
```

Load the two kernel modules the networking depends on. `overlay` is the union
filesystem containerd layers images with; `br_netfilter` lets iptables rules see
traffic crossing a Linux bridge, which is how pod traffic gets filtered:

```bash
cat <<'EOF' | sudo tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

sudo modprobe overlay
sudo modprobe br_netfilter
lsmod | grep -E 'overlay|br_netfilter'
```

Now the sysctl settings. Without these, Services silently fail to route:

```bash
cat <<'EOF' | sudo tee /etc/sysctl.d/99-kubernetes.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
vm.max_map_count                    = 262144
EOF

sudo sysctl --system
sysctl net.ipv4.ip_forward net.bridge.bridge-nf-call-iptables
```

The first three lines are what Kubernetes needs. `vm.max_map_count = 262144` is
there for Part 10: Elasticsearch refuses to start below that value, and it's a
host-level setting you can only make because Hetzner gives you real VMs. Setting it
now on every node saves a detour later; it's harmless for the plain cluster.

### 2.3 Container runtime — run on ALL THREE nodes

```bash
sudo apt-get update
sudo apt-get install -y containerd apt-transport-https ca-certificates curl gpg
```

The Ubuntu package ships an almost-empty config. Generate the full default, then
change one line:

```bash
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml >/dev/null
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
grep SystemdCgroup /etc/containerd/config.toml

sudo systemctl restart containerd
sudo systemctl enable containerd
sudo systemctl status containerd --no-pager
```

**Why `SystemdCgroup = true` matters.** A cgroup ("control group") is the kernel
feature that caps a process's CPU and memory. On a systemd machine, systemd owns
the cgroup tree. If containerd manages cgroups itself while systemd also does,
you get two managers fighting over one tree, and nodes go unstable under memory
pressure. The kubelet defaults to the systemd driver, so containerd must match.
**This mismatch is the number one cause of "my kubeadm cluster half-works".**

### 2.4 Install kubeadm, kubelet, kubectl — run on ALL THREE nodes

Set the version once so all three nodes match:

```bash
K8S_MINOR=1.35

sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://pkgs.k8s.io/core:/stable:/v${K8S_MINOR}/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg

echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v${K8S_MINOR}/deb/ /" \
  | sudo tee /etc/apt/sources.list.d/kubernetes.list

sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl cri-tools
sudo apt-mark hold kubelet kubeadm kubectl     # stop apt upgrading them behind your back
```

Point `crictl` at containerd. `crictl` is the low-level container debugger — the
tool you use when the API server is down and `kubectl` is useless:

```bash
cat <<'EOF' | sudo tee /etc/crictl.yaml
runtime-endpoint: unix:///run/containerd/containerd.sock
image-endpoint: unix:///run/containerd/containerd.sock
timeout: 10
EOF

sudo crictl version
```

Check the kubelet's state before you go further:

```bash
systemctl status kubelet --no-pager
```

It will be in a crash loop. **That is correct.** The kubelet starts, finds no
`/var/lib/kubelet/config.yaml`, exits, and systemd restarts it. `kubeadm init`
writes that file. Understanding this saves you panic later.

### 2.5 Initialise the control plane — CONTROL PLANE ONLY

```bash
# Hetzner lists the PUBLIC IP first in `hostname -I`, so pick the 10.0.0.x one
# explicitly. On the control plane this resolves to 10.0.0.10.
PRIVATE_IP=$(hostname -I | tr ' ' '\n' | grep '^10\.0\.' | head -1)
echo $PRIVATE_IP     # expect 10.0.0.10

sudo kubeadm init \
  --pod-network-cidr=192.168.0.0/16 \
  --apiserver-advertise-address=$PRIVATE_IP \
  --node-name=control-plane
```

What each flag does:

- `--pod-network-cidr` — the address range pods get IPs from. It must not overlap your VPC CIDR (10.0.0.0/16 here) and it must match what your CNI expects. Calico's default manifest expects `192.168.0.0/16`; Flannel expects `10.244.0.0/16`. Mismatching these is a classic silent failure.
- `--apiserver-advertise-address` — which IP the API server tells everyone to reach it on. On a multi-interface machine kubeadm guesses, sometimes wrong.
- `--node-name` — otherwise it uses the hostname, which you already set. Explicit is better.

Takes 2–4 minutes, mostly pulling images. **Copy the `kubeadm join ...` line from
the output** — you need it in 2.8. If you lose it:

```bash
sudo kubeadm token create --print-join-command
```

Set up your kubeconfig, exactly as the init output tells you:

```bash
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config

kubectl get nodes
```

```
NAME            STATUS     ROLES           AGE   VERSION
control-plane   NotReady   control-plane   1m    v1.35.x
```

**`NotReady` is expected.** There is no CNI yet, so the kubelet reports the
network as unconfigured. Confirm the reason rather than assuming it:

```bash
kubectl describe node control-plane | grep -A5 Conditions
kubectl get pods -n kube-system
```

CoreDNS will be `Pending` — it needs pod networking to schedule. Everything else
should be `Running`.

### 2.6 Install the CNI — CONTROL PLANE ONLY

Calico, not Flannel: Calico implements NetworkPolicy, which you need for CKS and
which Flannel doesn't support at all.

```bash
kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.29.1/manifests/calico.yaml

kubectl get pods -n kube-system -w      # Ctrl-C when calico-node is Running
kubectl get nodes                       # control-plane → Ready within ~90s
```

### 2.7 Join the workers — WORKERS ONLY

Paste the join command from 2.5 on each worker:

```bash
sudo kubeadm join 10.0.0.x:6443 --token abcdef.0123456789abcdef \
  --discovery-token-ca-cert-hash sha256:1234...
```

Read what it does before running it: the worker contacts the API server, verifies
the server's certificate against the hash you pass (so a hijacked endpoint can't
enrol your node), presents the token to prove it's authorised, and receives a
kubelet client certificate in return. That's the entire bootstrap trust model, and
CKS asks about it.

### 2.8 Verify — CONTROL PLANE

```bash
kubectl get nodes -o wide
```

```
NAME            STATUS   ROLES           AGE   VERSION   INTERNAL-IP
control-plane   Ready    control-plane   9m    v1.35.x   10.0.0.10
worker-1        Ready    <none>          2m    v1.35.x   10.0.0.11
worker-2        Ready    <none>          2m    v1.35.x   10.0.0.12
```

`ROLES <none>` on workers is normal — Kubernetes has no built-in worker role, it's
just a label. Add one for readability:

```bash
kubectl label node worker-1 worker-2 node-role.kubernetes.io/worker=
```

End-to-end smoke test:

```bash
kubectl run smoke --image=nginx
kubectl get pod smoke -o wide          # should land on a worker, not the control plane
kubectl run probe --image=busybox --rm -it --restart=Never -- wget -qO- <smoke-pod-ip>
kubectl delete pod smoke
```

If the second command hangs, pod networking is broken — check `kubectl get pods -n
kube-system` for a crash-looping `calico-node`.

**Resetting to a clean slate.** When a drill leaves the cluster in a mess, or you
want to rehearse the build from scratch, tear the cluster down without deleting the
VMs and start again from 2.5:

```bash
# on every node
sudo kubeadm reset -f
sudo rm -rf /etc/cni/net.d ~/.kube
sudo iptables -F && sudo iptables -t nat -F     # clear leftover Service rules
```

Re-running `kubeadm init` from memory is worth more than reading about it ten times,
so use this freely — it's the local-reset habit that a throwaway cluster would
otherwise give you, on the real nodes.

### 2.9 Optional: kubectl from your laptop

```bash
scp root@<control-plane-public-ip>:/root/.kube/config ./kubeconfig-lab
# edit the server: line, replace the private IP with the PUBLIC IP
# (macOS sed shown; on Linux drop the '' after -i)
sed -i '' "s|server: https://10\.0\.0\.[0-9]*|server: https://<public-ip>|" ./kubeconfig-lab
export KUBECONFIG=$PWD/kubeconfig-lab
kubectl get nodes
```

This fails with a certificate error, because the API server's certificate doesn't
list the public IP. Two ways forward — do the second one, it teaches you more:

1. `kubectl --insecure-skip-tls-verify get nodes` (works, learns you nothing)
2. Regenerate the API server certificate with the public IP included:

```bash
# on the control plane
sudo mv /etc/kubernetes/pki/apiserver.{crt,key} /tmp/
sudo kubeadm init phase certs apiserver --apiserver-cert-extra-sans=<public-ip>
sudo crictl rm -f $(sudo crictl ps -q --name kube-apiserver)   # force a restart
```

`kubeadm init phase` re-runs one slice of the install. Being comfortable with
phases is genuinely useful in CKA repair scenarios.

---

## Part 3 — Map the control plane vs the workers

This is the part that most study material skips and the exam assumes.

### 3.1 The control plane runs as static pods

```bash
ls -l /etc/kubernetes/manifests/
```

```
etcd.yaml  kube-apiserver.yaml  kube-controller-manager.yaml  kube-scheduler.yaml
```

These are **static pods**: the kubelet watches this directory and runs whatever it
finds, without asking the API server. That's the bootstrap trick — the API server
itself is started by a kubelet that doesn't need an API server.

```bash
kubectl get pods -n kube-system -o wide
```

Note that the pod names are suffixed with the node name (`kube-apiserver-control-plane`).
That suffix is the signature of a static pod. The API server knows about them only
as read-only "mirror pods" — try `kubectl delete pod kube-scheduler-control-plane`
and watch it come straight back, because the kubelet, not the API server, owns it.

### 3.2 What each component does — with a drill for each

| Component | Runs as | Job | Failure symptom |
|---|---|---|---|
| **kube-apiserver** | static pod, control plane | The only component that talks to etcd. Every read and write goes through it. Handles authentication, authorisation, admission. | `kubectl` returns connection refused |
| **etcd** | static pod, control plane | The database. Every object's desired and actual state. | API server won't start |
| **kube-scheduler** | static pod, control plane | Watches for pods with no `nodeName` and picks a node based on resources, affinity, taints. Writes the binding; does not start anything. | New pods stay `Pending` forever |
| **kube-controller-manager** | static pod, control plane | Runs ~30 reconciliation loops (Deployment, ReplicaSet, Node, ServiceAccount…). Drives actual state toward desired state. | Deleting a Deployment's pod doesn't recreate it |
| **kubelet** | **systemd unit, every node** | Talks to the container runtime. Starts/stops containers, reports node and pod status, runs probes. | Node goes `NotReady` after 40s |
| **kube-proxy** | DaemonSet, every node | Programs iptables/IPVS so Service ClusterIPs route to pod IPs. | Pods run, Services unreachable |

**Drill 1 — kill the scheduler, watch the symptom.**

```bash
sudo mv /etc/kubernetes/manifests/kube-scheduler.yaml /tmp/
kubectl get pods -n kube-system | grep scheduler        # gone within seconds

kubectl run stuck --image=nginx
kubectl get pod stuck                                    # Pending, indefinitely
kubectl describe pod stuck | tail -5                     # no scheduling events at all
kubectl get pod stuck -o jsonpath='{.spec.nodeName}'     # empty

sudo mv /tmp/kube-scheduler.yaml /etc/kubernetes/manifests/
kubectl get pod stuck -w                                 # scheduled and Running in ~10s
kubectl delete pod stuck
```

That's the entire mental model: the scheduler's only output is setting
`spec.nodeName`. Prove it by scheduling by hand with the scheduler still down —
set `nodeName: worker-1` in a pod manifest and it runs regardless.

**Drill 2 — read etcd directly.**

```bash
kubectl -n kube-system exec -it etcd-control-plane -- sh -c '
  ETCDCTL_API=3 etcdctl \
    --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key \
    member list -w table'
```

List every key Kubernetes stores — this makes the API's structure concrete:

```bash
kubectl -n kube-system exec -it etcd-control-plane -- sh -c '
  ETCDCTL_API=3 etcdctl --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key \
    get / --prefix --keys-only' | grep -v '^$' | head -40
```

Backup and restore is a guaranteed CKA task. Practise the backup now:

```bash
kubectl -n kube-system exec etcd-control-plane -- sh -c '
  ETCDCTL_API=3 etcdctl --endpoints=https://127.0.0.1:2379 \
    --cacert=/etc/kubernetes/pki/etcd/ca.crt \
    --cert=/etc/kubernetes/pki/etcd/server.crt \
    --key=/etc/kubernetes/pki/etcd/server.key \
    snapshot save /var/lib/etcd/snap.db'

sudo ls -lh /var/lib/etcd/snap.db
```

**Drill 3 — kubelet is not a pod.**

```bash
systemctl status kubelet --no-pager
sudo journalctl -u kubelet -n 50 --no-pager

cat /var/lib/kubelet/config.yaml | head -30      # the kubelet's own configuration
cat /etc/kubernetes/kubelet.conf                 # how it authenticates to the API server
```

On worker-1, stop it and watch the node go down:

```bash
sudo systemctl stop kubelet
# on the control plane, after ~40 seconds:
kubectl get nodes                                 # worker-1 NotReady
kubectl describe node worker-1 | grep -A8 Conditions
sudo systemctl start kubelet                      # back to Ready
```

Note what did *not* happen: the pods already running on worker-1 kept running.
The containers are owned by containerd, not the kubelet. Verify with
`sudo crictl ps` on worker-1 while the kubelet is stopped. This distinction —
kubelet reports and manages, containerd runs — is worth internalising.

**Drill 4 — kube-proxy and the iptables it writes.**

```bash
kubectl get daemonset -n kube-system kube-proxy
kubectl get pods -n kube-system -l k8s-app=kube-proxy -o wide   # one per node

# create a Service, then find its rules
kubectl create deployment web --image=nginx --replicas=2
kubectl expose deployment web --port=80
kubectl get svc web

# on any node:
sudo iptables-save -t nat | grep <cluster-ip-of-web>
```

You'll see a `KUBE-SERVICES` rule sending the ClusterIP to a `KUBE-SVC-` chain,
which distributes across `KUBE-SEP-` chains, one per pod endpoint. That's the
whole of Service networking. Delete a pod and re-run the grep to watch the
endpoint set change.

### 3.3 Certificates

CKS spends real time here, and CKA asks you to renew things.

```bash
sudo ls -l /etc/kubernetes/pki/
sudo kubeadm certs check-expiration
sudo openssl x509 -in /etc/kubernetes/pki/apiserver.crt -text -noout | head -20
```

Every component authenticates with a client certificate signed by the cluster CA
in `/etc/kubernetes/pki/ca.crt`. The `CN` (common name) is the username and the
`O` (organisation) is the group — that's how `kubeadm`'s admin kubeconfig gets
cluster-admin: it's in the `system:masters` group.

```bash
grep client-certificate-data ~/.kube/config | awk '{print $2}' | base64 -d \
  | openssl x509 -text -noout | grep Subject:
```

---

## Part 4 — First app: Pod → Deployment → Service

Build up the abstraction one layer at a time, and delete a pod at each layer to
see what changes.

### 4.1 A bare Pod

```bash
kubectl run web1 --image=nginx --port=80
kubectl get pod web1 -o wide
kubectl describe pod web1
kubectl logs web1
kubectl exec -it web1 -- sh -c 'curl -s localhost | head -5'

kubectl delete pod web1
kubectl get pods                # gone, permanently. Nothing recreates it.
```

A Pod is the smallest deployable unit: one or more containers sharing a network
namespace and IP. It has no self-healing. Nobody runs bare pods in production.

### 4.2 A Deployment

```bash
kubectl create deployment web --image=nginx --replicas=3
kubectl get deploy,rs,pods
```

Three objects, not one:

- **Deployment** — declares intent and manages rollouts
- **ReplicaSet** — keeps exactly N pods alive (created by the Deployment)
- **Pods** — created by the ReplicaSet

Prove the self-healing:

```bash
kubectl delete pod -l app=web --field-selector status.phase=Running | head -1
kubectl get pods -w         # a replacement appears within a second
```

Roll out a change and inspect the history:

```bash
kubectl set image deployment/web nginx=nginx:1.27
kubectl rollout status deployment/web
kubectl get rs                            # old ReplicaSet scaled to 0, new one to 3
kubectl rollout history deployment/web
kubectl rollout undo deployment/web
kubectl scale deployment/web --replicas=2
```

The old ReplicaSet stuck at 0 replicas is how `rollout undo` works — it scales the
old one back up. Seeing that makes rollbacks stop feeling like magic.

### 4.3 A Service

```bash
kubectl expose deployment web --port=80 --target-port=80 --name=web-svc
kubectl get svc web-svc
kubectl get endpoints web-svc     # or `kubectl get endpointslices`
```

The `Endpoints` object is the link: the Service selects pods by label, and the
endpoints controller keeps the pod IP list current. Break the link deliberately:

```bash
kubectl label pod -l app=web app=broken --overwrite
kubectl get endpoints web-svc     # <none> — the Service now selects nothing
kubectl label pod -l app=broken app=web --overwrite
kubectl get endpoints web-svc     # back
```

Test it from inside the cluster, which is what ClusterIP means:

```bash
kubectl run curl --image=curlimages/curl --rm -it --restart=Never -- \
  curl -s http://web-svc | head -5

# DNS also works, in several forms
kubectl run curl --image=curlimages/curl --rm -it --restart=Never -- \
  sh -c 'curl -s web-svc.default.svc.cluster.local | head -3'
```

Then expose it outside:

```bash
kubectl delete svc web-svc
kubectl expose deployment web --type=NodePort --port=80 --name=web-svc
kubectl get svc web-svc            # note the 3xxxx port

# from your laptop — the firewall already opened 30000-32767 to your IP
curl http://<any-node-public-ip>:<nodeport>
```

The three types you need cold:

| Type | Reachable from | Notes |
|---|---|---|
| ClusterIP | inside the cluster only | the default |
| NodePort | `<any-node-ip>:<30000-32767>` | opens the port on *every* node |
| LoadBalancer | external IP | needs a cloud controller; stays `Pending` forever on this kubeadm cluster |

The last row is worth seeing rather than reading:

```bash
kubectl expose deployment web --type=LoadBalancer --port=80 --name=lb-test
kubectl get svc lb-test          # EXTERNAL-IP: <pending>, forever
kubectl delete svc lb-test
```

Nothing is broken — a self-managed cluster has no cloud provider integration to
create a load balancer. That's precisely what EKS gives you and this doesn't.

---

## Part 5 — kubectl drills

Do these until you stop thinking about the syntax. Time yourself.

### 5.1 The imperative-to-YAML workflow

You will almost never hand-write a manifest from scratch in the exam. You generate
and edit:

```bash
k run nginx --image=nginx $do > pod.yaml
k create deployment web --image=nginx --replicas=3 $do > deploy.yaml
k create service clusterip web --tcp=80:80 $do > svc.yaml
k create configmap app-cfg --from-literal=KEY=value $do > cm.yaml
k create secret generic app-sec --from-literal=PASS=s3cret $do > sec.yaml
k create job hello --image=busybox $do -- echo hi > job.yaml
k create cronjob hello --image=busybox --schedule='*/1 * * * *' $do -- echo hi > cj.yaml
k create role dev --verb=get,list --resource=pods $do > role.yaml
k create rolebinding dev --role=dev --serviceaccount=default:app $do > rb.yaml
k create ns test $do > ns.yaml
k create quota myquota --hard=cpu=1,memory=1G,pods=2 $do > quota.yaml
```

Then `vi <file>`, adjust, `k apply -f <file>`. Memorise `$do`.

### 5.2 `explain` — the offline documentation

You can't use Google in the exam, but `kubectl explain` is always there:

```bash
k explain pod.spec
k explain pod.spec.containers
k explain pod.spec.containers.resources
k explain deployment.spec.strategy --recursive | head -30
k explain resourcequota.spec.hard
k explain pod.spec.securityContext            # CKS lives here
```

`--recursive` dumps the whole subtree of field names — the fastest way to recall
a field you half-remember.

### 5.3 `get` — output shaping

```bash
k get pods -o wide                                   # node, IP
k get pods -o yaml                                   # everything
k get pods -o json | jq '.items[].spec.containers[].image'
k get pods -o jsonpath='{.items[*].metadata.name}'
k get pods -o custom-columns='NAME:.metadata.name,NODE:.spec.nodeName,IMAGE:.spec.containers[0].image'
k get pods --sort-by=.metadata.creationTimestamp
k get pods --sort-by='.status.containerStatuses[0].restartCount'
k get pods -A -l app=web
k get pods --field-selector status.phase=Running
k get events --sort-by=.lastTimestamp | tail -20
k get all -n kube-system
k api-resources                                      # every resource type + short name
k api-resources --namespaced=false                   # cluster-scoped ones
```

`k api-resources` is how you recall that the short name for `resourcequota` is
`quota` and that `namespace` is cluster-scoped.

### 5.4 `describe` — the debugging default

```bash
k describe pod <name>          # read the Events at the bottom FIRST
k describe node worker-1       # capacity, allocatable, taints, running pods
k describe svc web-svc         # Endpoints line: empty means selector mismatch
k describe deploy web
```

For any "the pod isn't working" question, the answer is nearly always in the
Events section of `describe`, or in `k logs <pod> --previous` if it already
crashed.

### 5.5 `delete` and speed

```bash
k delete pod nginx $now              # skip the 30s grace period
k delete -f pod.yaml
k delete pods,svc -l app=web
k delete all --all -n test           # everything in a namespace
k delete ns test                     # nukes the namespace and its contents
```

### 5.6 Timed drills

Set a 4-minute timer per task. No documentation.

1. Create namespace `ecom`. In it, run a Deployment `api` with 3 replicas of `nginx:1.27`, expose it on port 80 as a NodePort, and curl it from another pod.
2. List every pod in every namespace, sorted by restart count, showing only name, namespace and node.
3. Create a pod `busy` running `busybox` that sleeps 3600, then get a shell in it and resolve `kubernetes.default.svc`.
4. Find which node has the most allocatable memory without using `describe`.
5. Scale `api` to 5, then roll it back to `nginx:1.25`, then undo the rollback.
6. Create a pod that requests 100m CPU and 64Mi memory with a limit of 200m/128Mi — starting from `k run ... $do`.

---

## Part 6 — Namespaces, ResourceQuotas, LimitRanges

### 6.1 Namespaces

```bash
k create ns dev
k get ns
k config set-context --current --namespace=dev
k config view --minify | grep namespace
```

Namespaces scope names, not machines. Two pods called `web` can coexist in `dev`
and `prod`. Some resources are cluster-scoped and can't be namespaced:

```bash
k api-resources --namespaced=false | head -20     # Node, PV, ClusterRole, Namespace itself
```

Cross-namespace DNS uses the full name — this is a common exam trip-up:

```bash
# from a pod in dev, reaching a service in prod:
curl http://web-svc.prod.svc.cluster.local
```

### 6.2 ResourceQuota — a ceiling for the whole namespace

```bash
cat <<'EOF' | k apply -f -
apiVersion: v1
kind: ResourceQuota
metadata:
  name: dev-quota
  namespace: dev
spec:
  hard:
    requests.cpu: "1"
    requests.memory: 1Gi
    limits.cpu: "2"
    limits.memory: 2Gi
    pods: "5"
    services: "3"
    configmaps: "5"
EOF

k describe quota dev-quota -n dev
```

Now hit the wall on purpose:

```bash
k run q1 --image=nginx -n dev
```

```
Error from server (Forbidden): pods "q1" is forbidden: failed quota: dev-quota:
must specify limits.cpu for: q1; limits.memory for: q1; requests.cpu for: q1; ...
```

**This is the behaviour to remember: once a namespace has a quota covering
compute, every pod must declare requests and limits, or it is rejected outright.**
That surprises people in production and it's a favourite exam question.

Do it correctly:

```bash
cat <<'EOF' | k apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: q1
  namespace: dev
spec:
  containers:
    - name: nginx
      image: nginx
      resources:
        requests: { cpu: 100m, memory: 64Mi }
        limits:   { cpu: 200m, memory: 128Mi }
EOF

k describe quota dev-quota -n dev     # Used column now non-zero
```

Then try to exceed it — set requests to `cpu: 2` and watch it be refused with a
different message ("exceeded quota").

### 6.3 LimitRange — defaults and bounds per object

A LimitRange fills in what the pod author omitted, which makes the quota usable:

```bash
cat <<'EOF' | k apply -f -
apiVersion: v1
kind: LimitRange
metadata:
  name: dev-limits
  namespace: dev
spec:
  limits:
    - type: Container
      default:                 # applied as limits if omitted
        cpu: 200m
        memory: 128Mi
      defaultRequest:          # applied as requests if omitted
        cpu: 100m
        memory: 64Mi
      min:
        cpu: 50m
        memory: 32Mi
      max:
        cpu: "1"
        memory: 512Mi
EOF

k describe limitrange dev-limits -n dev
```

Now the command that failed earlier works:

```bash
k run q2 --image=nginx -n dev
k get pod q2 -n dev -o jsonpath='{.spec.containers[0].resources}' | jq
```

The pod comes out with resources it never declared. That's the LimitRange
admission controller mutating the object on the way in.

Test the `max` bound:

```bash
k run q3 --image=nginx -n dev --overrides='
{"spec":{"containers":[{"name":"q3","image":"nginx","resources":{"limits":{"cpu":"2"}}}]}}'
# rejected: maximum cpu usage per Container is 1
```

The distinction to hold onto: **ResourceQuota is a total for the namespace,
LimitRange is a per-object rule.** They're routinely confused.

```bash
k delete ns dev
```

---

## Part 7 — ConfigMaps and Secrets

### 7.1 Creating them, four ways each

```bash
k create ns cfg && k config set-context --current --namespace=cfg

k create configmap app-cfg \
  --from-literal=APP_ENV=production \
  --from-literal=LOG_LEVEL=debug

echo "timeout=30" > app.properties
echo "retries=3" >> app.properties
k create configmap file-cfg --from-file=app.properties

printf 'DB_HOST=postgres\nDB_PORT=5432\n' > db.env
k create configmap env-cfg --from-env-file=db.env

k get cm app-cfg -o yaml
```

The difference matters: `--from-file` gives you **one key** whose name is the
filename and whose value is the whole file. `--from-env-file` parses the file and
gives you **one key per line**. Compare `k get cm file-cfg -o yaml` against
`k get cm env-cfg -o yaml` and the distinction sticks.

Secrets are identical in shape:

```bash
k create secret generic db-sec \
  --from-literal=DB_USER=admin \
  --from-literal=DB_PASS=s3cr3t

k get secret db-sec -o yaml
echo 'czNjcjN0' | base64 -d          # base64 is ENCODING, not encryption
```

Say it out loud once: a Secret is a ConfigMap with base64 encoding and slightly
different default RBAC. It is **not encrypted** unless you configure encryption at
rest on the API server — which is a CKS task, and now you know why it exists.

Use `stringData` when writing a Secret manifest by hand, and it does the encoding
for you:

```yaml
apiVersion: v1
kind: Secret
metadata: { name: db-sec }
type: Opaque
stringData:
  DB_PASS: s3cr3t          # written in plain text, stored base64
```

### 7.2 Injecting them — three mechanisms

**Individual keys as environment variables** — verbose, but lets you rename:

```yaml
env:
  - name: ENVIRONMENT
    valueFrom:
      configMapKeyRef: { name: app-cfg, key: APP_ENV }
  - name: PASSWORD
    valueFrom:
      secretKeyRef: { name: db-sec, key: DB_PASS }
```

**Everything at once**:

```yaml
envFrom:
  - configMapRef: { name: app-cfg }
  - secretRef:    { name: db-sec }
```

**As files in a volume** — the only option that picks up changes without a pod
restart:

```yaml
volumeMounts:
  - name: config
    mountPath: /etc/config
    readOnly: true
volumes:
  - name: config
    configMap: { name: file-cfg }
```

Put all three in one pod and inspect the result:

```bash
cat <<'EOF' | k apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: consumer
spec:
  containers:
    - name: app
      image: busybox
      command: ["sh", "-c", "sleep 3600"]
      env:
        - name: ENVIRONMENT
          valueFrom:
            configMapKeyRef: { name: app-cfg, key: APP_ENV }
        - name: PASSWORD
          valueFrom:
            secretKeyRef: { name: db-sec, key: DB_PASS }
      envFrom:
        - configMapRef: { name: env-cfg }
      volumeMounts:
        - name: cfg-vol
          mountPath: /etc/config
          readOnly: true
        - name: sec-vol
          mountPath: /etc/secret
          readOnly: true
  volumes:
    - name: cfg-vol
      configMap: { name: file-cfg }
    - name: sec-vol
      secret:
        secretName: db-sec
        defaultMode: 0400
EOF

k exec consumer -- env | grep -E 'ENVIRONMENT|PASSWORD|DB_'
k exec consumer -- ls -l /etc/config /etc/secret
k exec consumer -- cat /etc/config/app.properties
k exec consumer -- cat /etc/secret/DB_PASS       # decoded automatically
```

Now the live-update behaviour, which is the part people get wrong:

```bash
k create configmap file-cfg --from-literal=app.properties="timeout=99" \
  --dry-run=client -o yaml | k replace -f -

k exec consumer -- cat /etc/config/app.properties   # updates within ~60s
k exec consumer -- env | grep DB_HOST               # env vars NEVER update
```

**Volume-mounted config refreshes; environment variables are fixed at container
start.** If you change a ConfigMap consumed via `env`, you must restart the pods
(`k rollout restart deployment/x`).

```bash
k delete ns cfg
```

---

## Part 8 — RBAC

Four object types, and the whole model follows from how they combine.

| Object | Scope | Answers |
|---|---|---|
| Role | one namespace | what actions on what resources |
| ClusterRole | whole cluster | same, but cluster-wide or for cluster-scoped resources |
| RoleBinding | one namespace | who gets a Role (or a ClusterRole, applied to just that namespace) |
| ClusterRoleBinding | whole cluster | who gets a ClusterRole everywhere |

The combination that catches people: **a RoleBinding referencing a ClusterRole**
grants that ClusterRole's permissions *only inside the RoleBinding's namespace*.
That's how you reuse a standard role like `view` per-namespace without writing it
four times.

### 8.1 Build it up

```bash
k create ns team-a
k config set-context --current --namespace=team-a

# 1. an identity
k create serviceaccount reader

# 2. a permission set
k create role pod-reader --verb=get,list,watch --resource=pods

# 3. bind them
k create rolebinding reader-binding \
  --role=pod-reader \
  --serviceaccount=team-a:reader

k get role,rolebinding
k describe role pod-reader
k describe rolebinding reader-binding
```

### 8.2 Test permissions without impersonation gymnastics

`kubectl auth can-i` is the fastest tool here and it is exam-relevant:

```bash
k auth can-i list pods --as=system:serviceaccount:team-a:reader -n team-a
# yes

k auth can-i delete pods --as=system:serviceaccount:team-a:reader -n team-a
# no

k auth can-i list pods --as=system:serviceaccount:team-a:reader -n default
# no  — the Role is namespaced to team-a

k auth can-i --list --as=system:serviceaccount:team-a:reader -n team-a
# every permission that identity has
```

The username format `system:serviceaccount:<namespace>:<name>` is worth
memorising; it appears in audit logs and in every RBAC question.

### 8.3 Use the identity for real

```bash
cat <<'EOF' | k apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: api-caller
  namespace: team-a
spec:
  serviceAccountName: reader
  containers:
    - name: c
      image: curlimages/curl
      command: ["sh","-c","sleep 3600"]
EOF

k exec -it api-caller -- sh
```

Inside the pod, the ServiceAccount token is mounted automatically:

```sh
cd /var/run/secrets/kubernetes.io/serviceaccount
ls                    # ca.crt  namespace  token
TOKEN=$(cat token)

# allowed
curl -s --cacert ca.crt -H "Authorization: Bearer $TOKEN" \
  https://kubernetes.default.svc/api/v1/namespaces/team-a/pods | head -20

# forbidden — watch the 403 and read the message
curl -s --cacert ca.crt -H "Authorization: Bearer $TOKEN" \
  https://kubernetes.default.svc/api/v1/nodes
exit
```

That token is a JWT. Decode it and see the claims:

```bash
k create token reader -n team-a | cut -d. -f2 | base64 -d 2>/dev/null | jq
```

### 8.4 ClusterRoles

```bash
k get clusterroles | head -20
k describe clusterrole view          # a built-in, useful as a template

# grant the built-in `view` role, but only within team-a
k create rolebinding view-team-a \
  --clusterrole=view \
  --serviceaccount=team-a:reader

k auth can-i list services --as=system:serviceaccount:team-a:reader -n team-a   # yes
k auth can-i list services --as=system:serviceaccount:team-a:reader -n default  # no
```

Cluster-scoped resources need a ClusterRoleBinding — a RoleBinding can never grant
access to Nodes or PersistentVolumes, because those objects don't live in a
namespace:

```bash
k create clusterrole node-reader --verb=get,list --resource=nodes
k create clusterrolebinding node-reader-binding \
  --clusterrole=node-reader \
  --serviceaccount=team-a:reader

k auth can-i list nodes --as=system:serviceaccount:team-a:reader   # yes, now
```

### 8.5 Cleanup

```bash
k delete clusterrolebinding node-reader-binding
k delete clusterrole node-reader
k delete ns team-a
k config set-context --current --namespace=default
```

**Drill:** create a ServiceAccount `deployer` in `ci` that can create, update and
delete Deployments in `ci` only, and read (but not modify) ConfigMaps cluster-wide.
Verify every boundary with `auth can-i`. Four minutes.

---

## Part 10 — Observability: Prometheus, Grafana, EFK

This is why you sized the nodes at 4 vCPU / 8 GB. The stack below reserves roughly
5 vCPU and 9 GB across the two workers, leaving headroom for your Java and React
apps. Two deliberate choices keep it inside that budget: **one** Elasticsearch node
instead of the default three, and **Fluent Bit** (~100 MB) as the log shipper
instead of Logstash (~1.5 GB JVM) — so it's "EFK", not "ELK".

Run everything here from the control plane, with `kubectl` working (end of Part 2).

### 10.1 Storage — the prerequisite everything else needs

Prometheus and Elasticsearch both want a PersistentVolume. A kubeadm cluster has no
storage provisioner, so their PVCs sit `Pending` forever and nothing starts. Install
Rancher's local-path-provisioner, which carves volumes out of each node's 80 GB
disk, and make it the default StorageClass:

```bash
kubectl apply -f https://raw.githubusercontent.com/rancher/local-path-provisioner/v0.0.30/deploy/local-path-storage.yaml
kubectl patch storageclass local-path \
  -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
kubectl get storageclass         # local-path should show (default)
```

Volumes land under `/opt/local-path-provisioner` on whichever node the pod schedules
to. That's node-local, non-replicated storage — fine for a lab, never for real HA.

While you're here, install metrics-server so `kubectl top` works. On kubeadm the
kubelet serves a self-signed cert, so you must tell metrics-server to accept it:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch deployment metrics-server -n kube-system --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
kubectl -n kube-system rollout status deployment/metrics-server
kubectl top nodes
```

### 10.2 Install Helm

Everything from here is a Helm chart — which is also good CKA/CKS practice, since
Helm and the operator pattern both appear on the exams.

```bash
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
helm version
```

### 10.3 Metrics: kube-prometheus-stack

One chart installs Prometheus, Grafana, Alertmanager, node-exporter (a DaemonSet,
one per node) and kube-state-metrics, already wired together with dashboards
preloaded.

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

Write a values file that caps retention and resources — the defaults are sized for
a real cluster and will crowd your lab:

```bash
cat > monitoring-values.yaml <<'EOF'
prometheus:
  prometheusSpec:
    retention: 7d                      # cap the TSDB so the disk doesn't fill
    resources:
      requests: { cpu: 250m, memory: 1Gi }
      limits:   { cpu: "1",  memory: 2Gi }
    storageSpec:
      volumeClaimTemplate:
        spec:
          accessModes: ["ReadWriteOnce"]
          resources: { requests: { storage: 20Gi } }
grafana:
  adminPassword: admin                 # change for anything non-lab
  service:
    type: NodePort
    nodePort: 30030                     # reachable at http://<node-public-ip>:30030
  resources:
    requests: { cpu: 100m, memory: 256Mi }
    limits:   { cpu: 300m, memory: 384Mi }
alertmanager:
  alertmanagerSpec:
    resources:
      requests: { cpu: 50m,  memory: 128Mi }
      limits:   { cpu: 200m, memory: 256Mi }
EOF

helm install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace -f monitoring-values.yaml

kubectl -n monitoring get pods -w      # Ctrl-C once all are Running
```

Open Grafana at `http://<any-node-public-ip>:30030` (the firewall already allows
30000–32767 from your IP), log in as `admin` / `admin`, and open the preloaded
dashboard **"Kubernetes / Compute Resources / Cluster"**. That's your whole cluster's
CPU and memory, live.

Prometheus itself, if you want to write PromQL directly:

```bash
kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090
# then browse http://localhost:9090 and try:  sum(rate(container_cpu_usage_seconds_total[5m])) by (namespace)
```

### 10.4 Logs: EFK via the Elastic operator (ECK)

ECK (Elastic Cloud on Kubernetes) is the operator that manages Elasticsearch and
Kibana as custom resources — you declare an `Elasticsearch` object and it handles
the StatefulSet, TLS and credentials. Good operator-pattern practice.

```bash
helm repo add elastic https://helm.elastic.co
helm repo update
helm install elastic-operator elastic/eck-operator -n elastic-system --create-namespace
kubectl -n elastic-system rollout status statefulset/elastic-operator

kubectl create namespace logging
```

A single Elasticsearch node with a 1 GB heap (the JVM), 15 GB of storage, and
`vm.max_map_count` already satisfied from Part 2.2:

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: logging
  namespace: logging
spec:
  version: 8.15.3
  nodeSets:
    - name: default
      count: 1
      config:
        node.store.allow_mmap: true
      podTemplate:
        spec:
          containers:
            - name: elasticsearch
              env:
                - name: ES_JAVA_OPTS
                  value: "-Xms1g -Xmx1g"
              resources:
                requests: { cpu: 500m, memory: 2Gi }
                limits:   { memory: 2Gi }
      volumeClaimTemplates:
        - metadata: { name: elasticsearch-data }
          spec:
            accessModes: ["ReadWriteOnce"]
            resources: { requests: { storage: 15Gi } }
            storageClassName: local-path
EOF

kubectl -n logging get elasticsearch -w    # HEALTH goes green in ~2 min
```

Kibana, referencing that Elasticsearch and exposed as a NodePort:

```bash
cat <<'EOF' | kubectl apply -f -
apiVersion: kibana.k8s.elastic.co/v1
kind: Kibana
metadata:
  name: logging
  namespace: logging
spec:
  version: 8.15.3
  count: 1
  elasticsearchRef: { name: logging }
  http:
    service:
      spec:
        type: NodePort
  podTemplate:
    spec:
      containers:
        - name: kibana
          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { memory: 1Gi }
EOF

kubectl -n logging get svc logging-kb-http    # note the 3xxxx NodePort
```

ECK auto-generates the `elastic` user's password. Grab it, then reach Kibana over
**HTTPS** (ECK enables TLS by default) at `https://<node-public-ip>:<nodeport>`:

```bash
kubectl -n logging get secret logging-es-elastic-user \
  -o go-template='{{.data.elastic | base64decode}}{{"\n"}}'
# log in as: elastic / <that password>
```

Now ship logs. Fluent Bit runs as a DaemonSet (one per node), tails every
container's log file, and posts to Elasticsearch. Install it **into the `logging`
namespace** so it can read the ECK credentials secret:

```bash
helm repo add fluent https://fluent.github.io/helm-charts
helm repo update

cat > fluent-bit-values.yaml <<'EOF'
env:
  - name: ES_PASSWORD
    valueFrom:
      secretKeyRef:
        name: logging-es-elastic-user
        key: elastic
config:
  outputs: |
    [OUTPUT]
        Name             es
        Match            kube.*
        Host             logging-es-http.logging.svc
        Port             9200
        TLS              On
        TLS.Verify       Off
        HTTP_User        elastic
        HTTP_Passwd      ${ES_PASSWORD}
        Suppress_Type_Name On
        Logstash_Format  On
        Logstash_Prefix  kube
        Retry_Limit      False
EOF

helm install fluent-bit fluent/fluent-bit -n logging -f fluent-bit-values.yaml
kubectl -n logging rollout status daemonset/fluent-bit
```

In Kibana: **Stack Management → Data Views → Create**, pattern `kube-*`, time field
`@timestamp`. Then **Discover** shows every pod's logs. Generate some traffic to
confirm the pipeline:

```bash
kubectl run noisy --image=busybox --restart=Never -- \
  sh -c 'for i in $(seq 1 100); do echo "log line $i"; sleep 1; done'
# search kube-* in Kibana Discover for "log line"
```

### 10.5 Keep it from filling the disk

Prometheus is already capped at 7 days. Elasticsearch is not — indices grow until
the 15 GB PVC is full and the node goes read-only. Add an ILM (Index Lifecycle
Management) policy that deletes `kube-*` indices after a few days. Quickest via the
Kibana Dev Tools console (**Management → Dev Tools**):

```
PUT _ilm/policy/kube-logs
{ "policy": { "phases": { "delete": { "min_age": "3d", "actions": { "delete": {} } } } } }
```

then attach it to the index template Fluent Bit writes to. For a lab you can also
just delete old indices by hand: `DELETE kube-2026.09.*`.

Check the whole stack fits:

```bash
kubectl top nodes
kubectl top pods -A --sort-by=memory | head -20
```

You should see the two workers comfortably under their 8 GB, with Elasticsearch and
Prometheus as the two largest consumers.

---

## Part 9 — Teardown

Three `CX33` nodes cost roughly €0.03/hour together (~€19.50/month if left running,
excl. IPv4 and VAT). Hetzner bills hourly, but a **powered-off server still costs
the full rate** — Hetzner only stops billing when the server is *deleted*. So the
stop/start trick that saves money on EC2 does not save money here.

Two honest options:

**Delete and recreate.** With the infra in a script or Terraform, rebuilding is one
command and ~40 seconds, so for a lab this is usually the right call:

```bash
# hcloud
hcloud server delete control-plane worker-1 worker-2
hcloud network delete k8s-net
hcloud firewall delete k8s-fw

# Terraform
terraform destroy -var="admin_cidr=$(curl -s ifconfig.me)/32" -var="ssh_key_name=my-key"
```

Recreate later by re-running `k8s-lab-infra-hcloud.sh` or `terraform apply`, then
redo Part 2 from 2.2. Doing that init from memory is the point of the lab anyway.

**Keep it and just power off** if you want to preserve cluster state between
sessions and don't mind the ~€0.65/day. Public IPs are stable across a reboot on
Hetzner (unlike EC2), so no kubeconfig edit is needed:

```bash
hcloud server poweroff control-plane worker-1 worker-2
hcloud server poweron  control-plane worker-1 worker-2
```

---

## What to do next

Everything above is CKA territory except where noted. The natural follow-ons, in
rough order:

- **Scheduling** — taints, tolerations, node affinity, topology spread. Your two workers are enough to see all of it.
- **Storage** — PersistentVolume, PersistentVolumeClaim, StorageClass. Part 10 installs local-path-provisioner; for real block storage, install the Hetzner CSI driver, which provisions Cloud Volumes from PVCs (~€0.05/GB-month). Comparing the two makes the CSI abstraction concrete.
- **Cluster maintenance** — `kubectl drain` / `uncordon`, then `kubeadm upgrade plan` and `kubeadm upgrade apply`
- **etcd restore** — you took a snapshot in Part 3. Now delete a Deployment, restore the snapshot, and get the cluster back. This is the highest-weighted single CKA task.
- **NetworkPolicy** — Calico is already installed, so default-deny and selective-allow policies work. This is the bridge into CKS.
- **Then CKS proper** — API server audit policy, encryption at rest for Secrets, PodSecurity admission, seccomp and AppArmor profiles, image scanning, `kube-bench`.

Reset the cluster whenever you want a clean slate: `sudo kubeadm reset -f` on all
three nodes, then repeat from 2.5. Doing that init a second time from memory is
worth more than reading about it ten times.
