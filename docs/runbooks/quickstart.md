# Quickstart: Run Cartogra Locally

Docker Compose is the real local stack. Kubernetes is optional and only for the discovery-feature test path — see §2.

## Prereqs

JDK 25 · Docker · Node 22 + pnpm

## 1. Docker Compose — the actual local stack

`docker-compose.yml` is the production stack — local dev uses `docker-compose.dev.yml` only.

```bash
cp .env.example .env   # edit secrets if needed, defaults work for local

docker compose -f infra/docker-compose/docker-compose.dev.yml up -d
```

Wait for healthy:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml ps
```

Run services (each in its own terminal — Flyway migrates on startup):

```bash
./gradlew :services:gateway:bootRun     # :8080
./gradlew :services:registry:bootRun    # :8081
./gradlew :services:topology:bootRun    # :8082
./gradlew :services:ingestion:bootRun   # :8085
```

Frontend:

```bash
cd frontend && pnpm install && pnpm dev   # :3006
```

Stop / wipe:

```bash
docker compose -f infra/docker-compose/docker-compose.dev.yml down -v
```

Full env var table, troubleshooting → [`local-development.md`](local-development.md).

## 2. Kubernetes (optional) — already documented in `seed/`, don't redo it here

Two separate things, don't conflate:

- **Testing k8s discovery** (ingestion's `KubernetesWorker`) — fully scripted, no image builds, no secrets: [`seed/install-kind-kubectl.md`](../../seed/install-kind-kubectl.md) (cluster) → [`seed/k8s-discovery-test.md`](../../seed/k8s-discovery-test.md) (`kubectl apply -f seed/k8s-discovery-manifests.yaml` deploys 8 nginx stand-in services + registers the cluster + verifies Kafka events). Teardown: `bash seed/k8s-discovery-teardown.sh`.
- **Deploying Cartogra's own services** (`infra/k8s/base` + `overlays/dev`) — not local-dev ready: ConfigMaps point at in-cluster `postgres`/`kafka` that aren't in the manifests, and images are `ghcr.io/cartogra/*` you'd have to build/push yourself. Skip for local dev — Compose (§1) is the real local stack.
