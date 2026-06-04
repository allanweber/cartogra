#!/usr/bin/env bash
# seed/create-test-repos.sh
# Creates 8 fully runnable microservice repos from scratch:
#   4 on GitHub  → Trading-Journal org
#   4 on Azure   → allanweber.visualstudio.com / Cartogra project
#
# Prerequisites:
#   gh        (GitHub CLI, authenticated: gh auth login)
#   az        (Azure CLI + devops extension: az login  OR  export AZURE_DEVOPS_EXT_PAT=<token>)
#   git, curl, tar

set -euo pipefail

GITHUB_ORG="Trading-Journal"
AZURE_ORG="https://allanweber.visualstudio.com"
AZURE_PROJECT="Cartogra"
WORK_DIR="$(mktemp -d)"

cleanup() { rm -rf "$WORK_DIR"; }
trap cleanup EXIT

log() { echo "▶ $*"; }
ok()  { echo "✓ $*"; }
hr()  { echo "═══════════════════════════════════════════════════════"; }

# ─── Shared file writers ──────────────────────────────────────────────────────

write_dockerignore() {
  cat > .dockerignore <<'DEOF'
.git
build/
.gradle/
target/
*.md
.env
node_modules/
__pycache__/
*.pyc
.pytest_cache/
dist/
DEOF
}

write_readme() {
  local name="$1" description="$2" team="$3" stack="$4" port="$5"
  cat > README.md <<EOF
# ${name}

${description}

## Metadata
| Field | Value |
|-------|-------|
| Team  | ${team} |
| Stack | ${stack} |
| Port  | ${port} |

## Running locally

\`\`\`bash
docker build -t ${name} .
docker run -p ${port}:${port} ${name}
\`\`\`

Health: <http://localhost:${port}/actuator/health/live>
EOF
}

write_k8s() {
  local name="$1" port="$2" team="$3"
  mkdir -p k8s
  cat > k8s/deployment.yaml <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${name}
  namespace: prod
  labels:
    app: ${name}
    team: ${team}
    version: "1.0.0"
    environment: prod
spec:
  replicas: 2
  selector:
    matchLabels:
      app: ${name}
  template:
    metadata:
      labels:
        app: ${name}
        team: ${team}
        version: "1.0.0"
        environment: prod
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
      containers:
        - name: ${name}
          image: ghcr.io/${GITHUB_ORG}/${name}:1.0.0
          ports:
            - containerPort: ${port}
          resources:
            requests:
              cpu: "250m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health/live
              port: ${port}
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/ready
              port: ${port}
            initialDelaySeconds: 10
            periodSeconds: 5
          startupProbe:
            httpGet:
              path: /actuator/health
              port: ${port}
            failureThreshold: 30
            periodSeconds: 10
EOF

  cat > k8s/service.yaml <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${name}
  namespace: prod
  labels:
    app: ${name}
    team: ${team}
spec:
  type: ClusterIP
  selector:
    app: ${name}
  ports:
    - port: ${port}
      targetPort: ${port}
      protocol: TCP
EOF
}

# Generates valid OpenAPI 3 yaml, grouping multiple HTTP methods under the same path.
# Args: title port "METHOD /path Summary" ...
write_openapi() {
  local title="$1" port="$2"
  shift 2
  local entries=("$@")

  {
    printf 'openapi: "3.0.3"\ninfo:\n  title: "%s"\n  version: "1.0.0"\nservers:\n  - url: "http://localhost:%s"\npaths:\n' \
      "$title" "$port"

    # Collect unique ordered paths
    declare -A seen_paths
    local ordered_paths=()
    for entry in "${entries[@]}"; do
      local path
      path=$(echo "$entry" | awk '{print $2}')
      if [[ -z "${seen_paths[$path]+x}" ]]; then
        ordered_paths+=("$path")
        seen_paths[$path]=1
      fi
    done

    for path in "${ordered_paths[@]}"; do
      printf '  "%s":\n' "$path"
      for entry in "${entries[@]}"; do
        local e_method e_path e_summary
        e_method=$(echo "$entry" | awk '{print tolower($1)}')
        e_path=$(echo "$entry"  | awk '{print $2}')
        e_summary=$(echo "$entry" | cut -d' ' -f3-)
        if [[ "$e_path" == "$path" ]]; then
          printf '    %s:\n      summary: "%s"\n      responses:\n        "200":\n          description: OK\n' \
            "$e_method" "$e_summary"
        fi
      done
    done
  } > openapi.yaml
}

# ─── Java (Spring Boot) scaffold ──────────────────────────────────────────────
# Downloads a real working project from start.spring.io (includes Gradle wrapper).

scaffold_java() {
  local name="$1" port="$2" pkg="$3" description="$4"
  log "  Fetching Spring Initializr scaffold…"
  curl -sS "https://start.spring.io/starter.tgz" \
    -d "type=gradle-project-kotlin" \
    -d "language=java" \
    -d "groupId=io.tradingjournal" \
    -d "artifactId=${name}" \
    -d "name=${name}" \
    -d "description=${description}" \
    -d "packageName=${pkg}" \
    -d "javaVersion=21" \
    -d "dependencies=web,actuator" \
    | tar -xzf -
  chmod +x gradlew

  # Replace generated properties with YAML that enables K8s health probes
  rm -f src/main/resources/application.properties
  cat > src/main/resources/application.yml <<EOF
server:
  port: ${port}
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
      group:
        live:
          include: livenessState
        ready:
          include: readinessState
EOF

  cat > Dockerfile <<EOF
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
USER appuser
EXPOSE ${port}
HEALTHCHECK CMD curl -f http://localhost:${port}/actuator/health/live || exit 1
ENTRYPOINT ["sh", "-c", "exec java \${JAVA_OPTS:-} -jar app.jar"]
EOF
}

# ─── Node.js scaffold ─────────────────────────────────────────────────────────
# Args: name port "METHOD /express-path" ...

scaffold_node() {
  local name="$1" port="$2"
  shift 2
  local routes=("$@")

  cat > package.json <<EOF
{
  "name": "${name}",
  "version": "1.0.0",
  "private": true,
  "scripts": { "start": "node src/index.js" },
  "dependencies": { "express": "^4.21.0" }
}
EOF

  mkdir -p src
  local route_lines=""
  for entry in "${routes[@]}"; do
    local method path
    method=$(echo "$entry" | awk '{print tolower($1)}')
    path=$(echo "$entry"  | awk '{print $2}')
    route_lines+="app.${method}('${path}', (req, res) => res.json({ status: 'ok', path: '${path}' }));"$'\n'
  done

  cat > src/index.js <<JSEOF
'use strict';
const express = require('express');
const app = express();
const PORT = process.env.PORT || ${port};
app.use(express.json());

// K8s health probes
app.get('/actuator/health',       (_, res) => res.json({ status: 'UP' }));
app.get('/actuator/health/live',  (_, res) => res.json({ status: 'UP' }));
app.get('/actuator/health/ready', (_, res) => res.json({ status: 'UP' }));

// Domain routes
${route_lines}
app.listen(PORT, () => console.log('${name} listening on port ' + PORT));
JSEOF

  cat > Dockerfile <<EOF
FROM node:22-slim AS builder
WORKDIR /build
COPY package*.json ./
RUN npm install

FROM node:22-slim
RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /build/node_modules ./node_modules
COPY src ./src
COPY package.json ./
USER node
EXPOSE ${port}
HEALTHCHECK CMD curl -f http://localhost:${port}/actuator/health/live || exit 1
ENTRYPOINT ["node", "src/index.js"]
EOF
}

# ─── Python scaffold ──────────────────────────────────────────────────────────
# Args: name port "METHOD /path" ...

scaffold_python() {
  local name="$1" port="$2"
  shift 2
  local routes=("$@")

  cat > requirements.txt <<'REOF'
fastapi==0.115.0
uvicorn[standard]==0.32.0
REOF

  local route_defs=""
  local i=0
  for entry in "${routes[@]}"; do
    local method path fn
    method=$(echo "$entry" | awk '{print tolower($1)}')
    path=$(echo "$entry"   | awk '{print $2}')
    fn="route_${i}"
    i=$((i + 1))
    route_defs+="@app.${method}('${path}')"$'\n'
    route_defs+="def ${fn}(): return {'status': 'ok', 'path': '${path}'}"$'\n\n'
  done

  cat > main.py <<PYEOF
from fastapi import FastAPI

app = FastAPI(title="${name}", version="1.0.0")

@app.get("/actuator/health")
def health(): return {"status": "UP"}

@app.get("/actuator/health/live")
def liveness(): return {"status": "UP"}

@app.get("/actuator/health/ready")
def readiness(): return {"status": "UP"}

${route_defs}
PYEOF

  cat > Dockerfile <<EOF
FROM python:3.12-slim AS builder
WORKDIR /build
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

FROM python:3.12-slim
RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 appuser
WORKDIR /app
COPY --from=builder /install /usr/local
COPY main.py ./
USER appuser
EXPOSE ${port}
HEALTHCHECK CMD curl -f http://localhost:${port}/actuator/health/live || exit 1
ENTRYPOINT ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "${port}"]
EOF
}

# ─── Git helpers ──────────────────────────────────────────────────────────────

commit_and_push() {
  local remote="$1"
  git init -b main
  git add .
  git commit -m "feat: initial runnable service scaffold"
  git remote add origin "$remote"
  git push -u origin main
}

create_github_repo() {
  local name="$1" description="$2"
  log "Creating GitHub repo ${GITHUB_ORG}/${name}"
  gh repo create "${GITHUB_ORG}/${name}" \
    --public --description "${description}" --confirm 2>/dev/null \
    || log "  (already exists, will push to it)"
}

create_azure_repo() {
  local name="$1"
  log "Creating Azure repo ${name}"
  az repos create \
    --name "$name" --project "$AZURE_PROJECT" --org "$AZURE_ORG" \
    --output none 2>/dev/null \
    || log "  (already exists, will push to it)"
}

# ─── Bootstrap ────────────────────────────────────────────────────────────────

log "Configuring Azure DevOps defaults"
az devops configure --defaults "organization=${AZURE_ORG}" "project=${AZURE_PROJECT}"

# ═══════════════════════════════════════════════════════════════════════════════
# GitHub repos
# ═══════════════════════════════════════════════════════════════════════════════

# ── trade-service  (Java / Spring Boot) ───────────────────────────────────────
(
  create_github_repo "trade-service" "Core trade entry and management"
  d="${WORK_DIR}/trade-service" && mkdir -p "$d" && cd "$d"
  scaffold_java "trade-service" "8090" "io.tradingjournal.trade" "Core trade entry and management"
  write_readme  "trade-service" "Core trade entry and management" "trading-core" "Java 21 / Spring Boot" "8090"
  write_k8s     "trade-service" "8090" "trading-core"
  write_openapi "Trade Service" "8090" \
    "GET    /trades           List all trades" \
    "POST   /trades           Create a trade" \
    "GET    /trades/{id}      Get trade by ID" \
    "DELETE /trades/{id}      Delete a trade" \
    "GET    /trades/{id}/pnl  Calculate P&L for trade"
  write_dockerignore
  commit_and_push "https://github.com/${GITHUB_ORG}/trade-service.git"
  ok "trade-service → GitHub"
)

# ── portfolio-service  (Node.js / Express) ────────────────────────────────────
(
  create_github_repo "portfolio-service" "Portfolio aggregation and performance tracking"
  d="${WORK_DIR}/portfolio-service" && mkdir -p "$d" && cd "$d"
  scaffold_node "portfolio-service" "8091" \
    "GET    /portfolios" \
    "POST   /portfolios" \
    "GET    /portfolios/:id" \
    "GET    /portfolios/:id/summary" \
    "GET    /portfolios/:id/positions"
  write_readme  "portfolio-service" "Portfolio aggregation and performance tracking" "portfolio" "Node.js 22 / Express" "8091"
  write_k8s     "portfolio-service" "8091" "portfolio"
  write_openapi "Portfolio Service" "8091" \
    "GET  /portfolios               List all portfolios" \
    "POST /portfolios               Create a portfolio" \
    "GET  /portfolios/{id}          Get portfolio by ID" \
    "GET  /portfolios/{id}/summary  Portfolio performance summary" \
    "GET  /portfolios/{id}/positions Open positions"
  write_dockerignore
  commit_and_push "https://github.com/${GITHUB_ORG}/portfolio-service.git"
  ok "portfolio-service → GitHub"
)

# ── market-data-service  (Python / FastAPI) ───────────────────────────────────
(
  create_github_repo "market-data-service" "Real-time and historical market data ingestion"
  d="${WORK_DIR}/market-data-service" && mkdir -p "$d" && cd "$d"
  scaffold_python "market-data-service" "8092" \
    "GET  /quotes/{symbol}" \
    "GET  /quotes/{symbol}/history" \
    "GET  /symbols" \
    "POST /subscriptions"
  write_readme  "market-data-service" "Real-time and historical market data ingestion" "data" "Python 3.12 / FastAPI" "8092"
  write_k8s     "market-data-service" "8092" "data"
  write_openapi "Market Data Service" "8092" \
    "GET  /quotes/{symbol}         Latest quote for symbol" \
    "GET  /quotes/{symbol}/history Historical OHLCV data" \
    "GET  /symbols                 List available symbols" \
    "POST /subscriptions           Subscribe to real-time feed"
  write_dockerignore
  commit_and_push "https://github.com/${GITHUB_ORG}/market-data-service.git"
  ok "market-data-service → GitHub"
)

# ── notification-service  (Node.js / Express) ─────────────────────────────────
(
  create_github_repo "notification-service" "Email, push, and in-app alert delivery"
  d="${WORK_DIR}/notification-service" && mkdir -p "$d" && cd "$d"
  scaffold_node "notification-service" "8093" \
    "POST /notifications" \
    "GET  /notifications/:userId" \
    "PUT  /notifications/:id/read" \
    "GET  /preferences/:userId" \
    "PUT  /preferences/:userId"
  write_readme  "notification-service" "Email, push, and in-app alert delivery" "platform" "Node.js 22 / Express" "8093"
  write_k8s     "notification-service" "8093" "platform"
  write_openapi "Notification Service" "8093" \
    "POST /notifications               Send a notification" \
    "GET  /notifications/{userId}      Get notifications for user" \
    "PUT  /notifications/{id}/read     Mark notification as read" \
    "GET  /preferences/{userId}        Get delivery preferences" \
    "PUT  /preferences/{userId}        Update delivery preferences"
  write_dockerignore
  commit_and_push "https://github.com/${GITHUB_ORG}/notification-service.git"
  ok "notification-service → GitHub"
)

# ═══════════════════════════════════════════════════════════════════════════════
# Azure repos
# ═══════════════════════════════════════════════════════════════════════════════

# ── analytics-service  (Python / FastAPI) ─────────────────────────────────────
(
  create_azure_repo "analytics-service"
  d="${WORK_DIR}/analytics-service" && mkdir -p "$d" && cd "$d"
  scaffold_python "analytics-service" "8094" \
    "GET /analytics/winrate" \
    "GET /analytics/drawdown" \
    "GET /analytics/sharpe" \
    "GET /analytics/calendar"
  write_readme  "analytics-service" "Trade performance analytics and statistics" "data" "Python 3.12 / FastAPI" "8094"
  write_k8s     "analytics-service" "8094" "data"
  write_openapi "Analytics Service" "8094" \
    "GET /analytics/winrate   Win-rate percentage over period" \
    "GET /analytics/drawdown  Maximum drawdown metrics" \
    "GET /analytics/sharpe    Sharpe ratio calculation" \
    "GET /analytics/calendar  Daily P&L calendar heatmap"
  write_dockerignore
  commit_and_push "${AZURE_ORG}/${AZURE_PROJECT}/_git/analytics-service"
  ok "analytics-service → Azure"
)

# ── user-profile-service  (Java / Spring Boot) ────────────────────────────────
(
  create_azure_repo "user-profile-service"
  d="${WORK_DIR}/user-profile-service" && mkdir -p "$d" && cd "$d"
  scaffold_java "user-profile-service" "8095" "io.tradingjournal.userprofile" "User accounts, preferences, and broker connections"
  write_readme  "user-profile-service" "User accounts, preferences, and broker connections" "platform" "Java 21 / Spring Boot" "8095"
  write_k8s     "user-profile-service" "8095" "platform"
  write_openapi "User Profile Service" "8095" \
    "GET    /users/{id}                    Get user profile" \
    "PUT    /users/{id}                    Update user profile" \
    "GET    /users/{id}/brokers            List broker connections" \
    "POST   /users/{id}/brokers            Add broker connection" \
    "DELETE /users/{id}/brokers/{brokerId} Remove broker connection"
  write_dockerignore
  commit_and_push "${AZURE_ORG}/${AZURE_PROJECT}/_git/user-profile-service"
  ok "user-profile-service → Azure"
)

# ── report-generator  (Node.js / Express) ─────────────────────────────────────
(
  create_azure_repo "report-generator"
  d="${WORK_DIR}/report-generator" && mkdir -p "$d" && cd "$d"
  scaffold_node "report-generator" "8096" \
    "POST /reports" \
    "GET  /reports/:id" \
    "GET  /reports/:id/download" \
    "GET  /reports/scheduled"
  write_readme  "report-generator" "On-demand and scheduled trade report export" "portfolio" "Node.js 22 / Express" "8096"
  write_k8s     "report-generator" "8096" "portfolio"
  write_openapi "Report Generator" "8096" \
    "POST /reports               Trigger report generation" \
    "GET  /reports/{id}          Get report status" \
    "GET  /reports/{id}/download Download completed report" \
    "GET  /reports/scheduled     List scheduled reports"
  write_dockerignore
  commit_and_push "${AZURE_ORG}/${AZURE_PROJECT}/_git/report-generator"
  ok "report-generator → Azure"
)

# ── risk-engine  (Java / Spring Boot) ─────────────────────────────────────────
(
  create_azure_repo "risk-engine"
  d="${WORK_DIR}/risk-engine" && mkdir -p "$d" && cd "$d"
  scaffold_java "risk-engine" "8097" "io.tradingjournal.risk" "Position sizing, exposure limits, and drawdown alerts"
  write_readme  "risk-engine" "Position sizing, exposure limits, and drawdown alerts" "trading-core" "Java 21 / Spring Boot" "8097"
  write_k8s     "risk-engine" "8097" "trading-core"
  write_openapi "Risk Engine" "8097" \
    "GET  /risk/exposure  Current exposure across all positions" \
    "GET  /risk/limits    Configured position limits" \
    "POST /risk/check     Validate a trade against risk rules" \
    "GET  /risk/alerts    Active risk alerts" \
    "PUT  /risk/limits    Update risk limit configuration"
  write_dockerignore
  commit_and_push "${AZURE_ORG}/${AZURE_PROJECT}/_git/risk-engine"
  ok "risk-engine → Azure"
)

hr
echo " Done — 8 runnable repos created and pushed."
echo ""
echo " GitHub (Trading-Journal):"
echo "   trade-service          Java 21 / Spring Boot  :8090"
echo "   portfolio-service      Node.js 22 / Express   :8091"
echo "   market-data-service    Python 3.12 / FastAPI  :8092"
echo "   notification-service   Node.js 22 / Express   :8093"
echo ""
echo " Azure (allanweber / Cartogra):"
echo "   analytics-service      Python 3.12 / FastAPI  :8094"
echo "   user-profile-service   Java 21 / Spring Boot  :8095"
echo "   report-generator       Node.js 22 / Express   :8096"
echo "   risk-engine            Java 21 / Spring Boot  :8097"
echo ""
echo " Each repo: Dockerfile  openapi.yaml  k8s/  README  + runnable source"
hr
