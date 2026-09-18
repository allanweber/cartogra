#!/usr/bin/env bash
#
# k8s-lab-infra-hcloud.sh
# Provisions three empty Ubuntu 24.04 VMs on Hetzner Cloud for a kubeadm lab:
#   - one private network (10.0.0.0/16), subnet 10.0.0.0/24
#   - one firewall (SSH + API server + NodePort range, restricted to your IP)
#   - three CX33 nodes (4 vCPU / 8 GB / 80 GB) with fixed private IPs .10/.11/.12
#
# Nothing Kubernetes-related is installed. Do that by hand, following the guide.
#
# Prerequisites:
#   - hcloud CLI installed and a context created:  hcloud context create k8s-lab
#   - an SSH key uploaded:  hcloud ssh-key create --name my-key --public-key-from-file ~/.ssh/id_ed25519.pub
#
# Usage:
#   export ADMIN_CIDR="$(curl -s -4 ifconfig.me)/32"   # your public IPv4, for the firewall
#                                                       # (force -4: plain ifconfig.me can return
#                                                       # IPv6, and /32 on an IPv6 addr breaks
#                                                       # `hcloud firewall add-rule`)
#   export SSH_KEY_NAME=my-key
#   bash k8s-lab-infra-hcloud.sh
#
# Re-running is safe-ish: it skips resources that already exist by name.

set -euo pipefail

# ---------------------------------------------------------------- configuration
CLUSTER_NAME="${CLUSTER_NAME:-kubeadm-lab}"
NETWORK_NAME="${NETWORK_NAME:-k8s-net}"
FIREWALL_NAME="${FIREWALL_NAME:-k8s-fw}"
NETWORK_ZONE="${NETWORK_ZONE:-eu-central}"   # covers nbg1, fsn1, hel1
LOCATION="${LOCATION:-nbg1}"                 # Nuremberg. fsn1 = Falkenstein, hel1 = Helsinki
IMAGE="${IMAGE:-ubuntu-24.04}"
SSH_KEY_NAME="${SSH_KEY_NAME:?set SSH_KEY_NAME to the name of an uploaded hcloud ssh-key}"
ADMIN_CIDR="${ADMIN_CIDR:?set ADMIN_CIDR to your public IP as a CIDR, e.g. 203.0.113.7/32}"
if [[ ! "$ADMIN_CIDR" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}/32$ ]]; then
  echo "ADMIN_CIDR must be an IPv4 /32, got: $ADMIN_CIDR (did 'curl ifconfig.me' return IPv6? use -4)" >&2
  exit 1
fi

# 4 vCPU / 8 GB / 80 GB. This is CX33 as of 2026 (was CX32 in the Gen3 line).
# If `hcloud server create` errors with "server type not found", run
#   hcloud server-type list | grep -i '8 GB'
# and set SERVER_TYPE to the exact name shown.
SERVER_TYPE="${SERVER_TYPE:-cx33}"

# node name -> fixed private IP
declare -A NODES=(
  [control-plane]=10.0.0.10
  [worker-1]=10.0.0.11
  [worker-2]=10.0.0.12
)

log() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
exists() { hcloud "$1" describe "$2" >/dev/null 2>&1; }

# ------------------------------------------------------------------- network
if exists network "$NETWORK_NAME"; then
  log "network $NETWORK_NAME already exists, skipping"
else
  log "creating network $NETWORK_NAME (10.0.0.0/16)"
  hcloud network create --name "$NETWORK_NAME" --ip-range 10.0.0.0/16
  hcloud network add-subnet "$NETWORK_NAME" \
    --network-zone "$NETWORK_ZONE" --type cloud --ip-range 10.0.0.0/24
fi

# ------------------------------------------------------------------ firewall
# Rules apply to the PUBLIC interface only. Hetzner cloud firewalls do not filter
# traffic inside a private network, so node-to-node (etcd, kubelet, Calico) is
# unrestricted by design — no self-referencing rule needed.
if exists firewall "$FIREWALL_NAME"; then
  log "firewall $FIREWALL_NAME already exists, skipping create"
else
  log "creating firewall $FIREWALL_NAME"
  hcloud firewall create --name "$FIREWALL_NAME"
fi

# Rules are (re)applied every run regardless of whether the firewall object was
# just created or already existed — a prior run can die mid-add-rule (e.g. a bad
# ADMIN_CIDR) and leave the firewall present but empty; "already exists" alone
# must never be read as "rules are in place".
if [ "$(hcloud firewall describe "$FIREWALL_NAME" -o json | grep -c '"direction"')" -eq 0 ]; then
  log "firewall $FIREWALL_NAME has no rules, adding (SSH + 6443 + NodePorts from $ADMIN_CIDR)"
  hcloud firewall add-rule "$FIREWALL_NAME" --direction in --protocol tcp \
    --port 22          --source-ips "$ADMIN_CIDR" --description "SSH"
  hcloud firewall add-rule "$FIREWALL_NAME" --direction in --protocol tcp \
    --port 6443        --source-ips "$ADMIN_CIDR" --description "Kubernetes API"
  hcloud firewall add-rule "$FIREWALL_NAME" --direction in --protocol tcp \
    --port 30000-32767 --source-ips "$ADMIN_CIDR" --description "NodePort range"
  hcloud firewall add-rule "$FIREWALL_NAME" --direction in --protocol icmp \
    --source-ips "$ADMIN_CIDR" --description "ping"
else
  log "firewall $FIREWALL_NAME already has rules, skipping"
fi

# --------------------------------------------------------------------- nodes
for NAME in control-plane worker-1 worker-2; do
  PRIVATE_IP="${NODES[$NAME]}"
  if exists server "$NAME"; then
    log "server $NAME already exists, skipping"
    continue
  fi
  log "creating $NAME ($SERVER_TYPE) with private IP $PRIVATE_IP"
  hcloud server create \
    --name "$NAME" \
    --type "$SERVER_TYPE" \
    --image "$IMAGE" \
    --location "$LOCATION" \
    --ssh-key "$SSH_KEY_NAME" \
    --network "$NETWORK_NAME" \
    --firewall "$FIREWALL_NAME" \
    --label "cluster=$CLUSTER_NAME" \
    --label "role=$( [ "$NAME" = control-plane ] && echo control-plane || echo worker )"
  # pin the private IP (create attaches with a dynamic one; re-attach at the fixed IP)
  hcloud server detach-from-network "$NAME" --network "$NETWORK_NAME" >/dev/null 2>&1 || true
  hcloud server attach-to-network "$NAME" --network "$NETWORK_NAME" --ip "$PRIVATE_IP"
done

# ------------------------------------------------------------------- summary
log "done. Nodes:"
hcloud server list -o columns=name,ipv4,private_net,status,type

cat <<EOF

Next:
  ssh root@<control-plane-public-ip>     # Hetzner Ubuntu logs in as root
Then follow the guide from Part 2.2. The control plane's private IP is 10.0.0.10.

Tear down with:
  hcloud server delete control-plane worker-1 worker-2
  hcloud network delete $NETWORK_NAME
  hcloud firewall delete $FIREWALL_NAME
EOF
