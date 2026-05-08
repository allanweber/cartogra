# BIP 0.28 — Cartogra Launch Announcement

**Status:** Ready for Publishing (May 2026)  
**Platforms:** X (Twitter), LinkedIn, Blog  
**Audience:** Engineering teams, DevOps practitioners, open-source community  

---

## 📌 X Thread (7 tweets)

**Tweet 1 — Hook**
```
🚀 Excited to announce: Cartogra is live!

After months of building, we're launching a living service registry 
that fights the one constant in microservices: **staleness**.

Your services are always changing. Your docs shouldn't lag behind.

Learn more → [link]

#microservices #openSource #devOps
```

**Tweet 2 — Problem**
```
The problem we're solving:

Service catalogs go stale within weeks. Teams stop trusting them.
API contracts break in production. Topology changes surprise you.
Incident response becomes a guessing game.

This happens everywhere, and it's preventable.
```

**Tweet 3 — Solution Intro**
```
Enter Cartogra: a service registry that **learns from your actual infrastructure**.

Three core pillars:
1️⃣ **Registry** — Always-fresh service inventory
2️⃣ **Topology** — Real-time dependency graphs & blast radius
3️⃣ **Contracts** — Break-change detection before production

All powered by a single source of truth: your running code.
```

**Tweet 4 — Technical Credibility**
```
Built on battle-tested foundations:

✅ Java 25 + Spring Boot 4 (async, virtual threads, pattern matching)
✅ PostgreSQL with multi-tenancy + RLS
✅ OpenTelemetry for complete tracing
✅ Kafka for event-driven sync
✅ gRPC for internal service communication
✅ TanStack Start for the frontend

Production-ready architecture from day one.
```

**Tweet 5 — Observability**
```
Complete observability built in:

📊 Structured JSON logging with trace correlation
🔍 Distributed tracing with OTel + Grafana stack
📈 Prometheus metrics on every service
🚨 Health probes + liveness indicators

Every request, fully traceable from frontend to database.
```

**Tweet 6 — Call to Action (Dev)**
```
For developers:

⭐ Star the repo: [GitHub link]
📖 Read the docs: [docs link]
🐳 Try locally: docker-compose up

Feedback is gold. Issues, PRs, discussions: all welcome.

Phase 0 (registry + topology) ships now.
Phase 1 (contract validation) comes next.
```

**Tweet 7 — Closing**
```
Special thanks to the team who built this.

The journey from idea → production registry is long.
Now comes the fun part: putting it in your hands.

Welcome to Cartogra. Let's make microservices less chaotic.

#OpenSource #K8s #SRE #DevOps
```

---

## 🔗 LinkedIn Post

**Headline:**  
"Introducing Cartogra: The Living Service Registry Your Microservices Infrastructure Deserves"

**Body:**

For any organization running microservices at scale, this feels familiar:

- Your service catalog is three months old and unreliable
- You have no idea what's actually calling what in production
- Breaking changes hit production because contracts slipped out of sync
- Incident response turns into detective work

At its core, this is a **data staleness problem**. Services evolve constantly. Documentation and governance models don't keep up.

Today, I'm thrilled to introduce **Cartogra**—a living service registry built to stay fresh, automatically.

**Three Pillars**

1. **Registry**: Always-current service inventory with ownership, API specs, and team metadata
2. **Topology**: Real-time dependency graphs with blast radius analysis
3. **Contracts**: Break-change detection before they hit production

**Why Built This Way**

We chose boring, proven technology:
- Java 25 + Spring Boot 4 for type safety and performance
- PostgreSQL for structured multi-tenant data
- Kafka for reliable service-to-service events
- OpenTelemetry for complete observability
- gRPC for internal communication (no REST chaos)

The architecture prioritizes **operational clarity**: every decision traceable, every request correlated through distributed tracing.

**What's Next**

Phase 0 is live now (registry + topology). Phase 1 adds contract validation and breaking-change detection.

If you build microservices, deploy on Kubernetes, or run SRE practices—give it a look. The code is open source. Feedback, PRs, and real-world horror stories all welcome.

**GitHub**: [link]  
**Docs**: [link]  
**Community**: Discussions and issues welcome  

#microservices #observability #openSource #SRE #DevOps

---

## 📰 Blog Post (1000–1500 words)

**Title:** "Cartogra: Why We Built a Living Service Registry (And Why You Might Need One)"

**Excerpt:**
```
Microservices are powerful. They're also fragile. Every new service, 
every API change, every dependency shift creates complexity. Teams 
try to document it all. They fail. We wanted to stop failing.
```

### The Problem No One Talks About

Run microservices long enough, and you hit the same wall: **your documentation will always be out of sync with reality**.

Not because your team is lazy. Because reality moves faster than documentation. A developer ships a new endpoint, updates a contract, connects a new Kafka topic—but updating the service catalog? That lives in a spreadsheet nobody opened in six months.

Fast forward three months:
- Your API docs describe endpoints that no longer exist
- You think Service A calls Service B, but the graph says otherwise
- Nobody knows who owns the auth service anymore
- Incidents turn into panicked Slack hunts: "Who knows this code?"

This isn't a documentation problem. It's an **architecture problem**.

### Why Existing Tools Weren't Enough

We looked at the landscape:
- **API gateways** (Envoy, Kong): Good for routing, don't track service metadata
- **Service meshes** (Istio, Linkerd): Great for traffic, not for governance
- **Static registries** (Consul, Eureka): Manual updates = stale data
- **API portals** (Swagger, OpenAPI registries): Nice UI, still requires manual sync

Each solved part of the problem. None solved the **staleness problem**—the fundamental mismatch between code reality and documented truth.

So we built Cartogra.

### Three Core Pillars

**1. The Registry**

A single source of truth for every service in your infrastructure:
- Name, description, ownership (team + contacts)
- API specs (OpenAPI, protobuf)
- Dependencies (what it calls, what calls it)
- Environment URLs (staging, production)
- Health status and operational tags

Unlike manual catalogs, the registry feeds from real infrastructure events. When a service deploys, updates its API, or changes teams—those facts propagate automatically.

**2. The Topology**

A dependency graph that answers the questions nobody can answer manually:
- "If Service X goes down, what breaks?"
- "What's the blast radius of this API change?"
- "Which services form a cycle?"
- "Can we deploy this change independently?"

Built on recursive SQL queries that handle real-world graph complexity: cycles, deep hierarchies, cross-team dependencies. Updates happen in real-time as services change.

**3. Contract Validation**

APIs break. New fields get added. Enums change. Type mismatches cascade down the dependency chain.

Cartogra detects breaking changes before they hit production:
- Compare old vs. new API specs
- Flag incompatibilities with downstream consumers
- Suggest migration paths

Contracts are versioned, tracked, and validated on every deployment.

### Technical Choices

We made specific technology bets:

**Backend:** Java 25 + Spring Boot 4  
Why? Type safety, performance, and the ecosystem. Virtual threads for async I/O without thread explosion. Records for immutable DTOs. Pattern matching for clean control flow.

**Database:** PostgreSQL 16 with row-level security  
Why? Structured data with multi-tenancy built in. RLS policies prevent cross-tenant leaks at the database level. JSONB for flexible metadata.

**Messaging:** Apache Kafka  
Why? Events are the source of truth. When a service registers, updates its API, or changes teams—that's a Kafka event. Consumers (topology builder, contract validator, notification system) subscribe and react.

**Observability:** OpenTelemetry + Grafana stack  
Why? Trace every request, correlate logs, see metrics. No observability surprises when debugging issues.

**Internal RPC:** gRPC + Protobuf  
Why? REST between services is chaos. gRPC ensures type safety and binary efficiency. Protobuf schemas version gracefully.

**Frontend:** TanStack Start with TypeScript  
Why? File-based routing that scales. React Server Components for data fetching without complexity. Full type safety end-to-end.

### How It Works

**In practice:**

1. A new microservice boots in Kubernetes
2. It registers itself with the Cartogra registry (name, API spec, team ownership)
3. A `ServiceRegistered` event hits Kafka
4. The topology builder consumes the event, updates the dependency graph
5. The contract validator ingests the API spec, compares against known consumers
6. Grafana dashboards update in real-time
7. Teams get notified: "New service registered"

Later, the same service deploys with a breaking API change:
1. The new API spec is pushed to the registry
2. Contract validator runs: "This change breaks Consumer X"
3. Notification: "Deprecation warning: Consumer X will fail in 30 days"
4. DevOps team has time to coordinate instead of dealing with a 3 AM incident

### Architecture Under the Hood

**Multi-tenancy from day one:** Every domain entity has `tenant_id`. PostgreSQL RLS policies prevent accidental cross-tenant leaks.

**Distributed tracing:** Every request gets a 32-character hex trace ID. Propagated through HTTP headers, Kafka messages, logs. Correlates frontend → gateway → registry service → database queries.

**Structured JSON logging:** Logs include trace IDs, service names, timestamps. No grep-hunting for errors—just structured data into Grafana Loki.

**Event-driven sync:** Services communicate via Kafka. One service doesn't know about others directly. Decoupled, resilient, easy to scale.

### What's Shipping Today

**Phase 0 — Foundation**
- ✅ Service registry with ownership
- ✅ Real-time dependency topology
- ✅ Multi-tenant isolation
- ✅ OpenTelemetry tracing
- ✅ Grafana observability dashboard

**Phase 1 — Contracts** (coming soon)
- 🔜 API contract validation
- 🔜 Breaking-change detection
- 🔜 Multi-version support
- 🔜 Deprecation warnings

**Phase 2+ — Intelligence** (roadmap)
- 📋 Claude-powered service Q&A
- 📋 Health scoring
- 📋 Deployment impact analysis

### Getting Started

```bash
# Clone and start locally
git clone https://github.com/[org]/cartogra.git
cd cartogra
docker-compose up

# Frontend: http://localhost:3000
# Registry API: http://localhost:8081
# Grafana: http://localhost:3001
```

Register your first service:
```bash
curl -X POST http://localhost:8081/api/v1/services \
  -H "Content-Type: application/json" \
  -d '{
    "name": "payment-service",
    "team": "platform",
    "apiSpec": {...}
  }'
```

### Why This Matters

Microservices are here to stay. But they're only powerful if teams can understand them.

A living registry removes the mismatch between code and documentation. It scales with your infrastructure instead of becoming a burden.

Cartogra won't fix your organizational problems. But it'll give you better visibility, faster incident response, and fewer surprises.

### What's Next

We're open-sourcing this. The code is on GitHub. Docs are on our website. Community discussions are open.

If you run Kubernetes, manage microservices, practice SRE—give it a try. Break it. Tell us what you need next.

The goal: make microservices governance a solved problem, not a crisis-management nightmare.

---

**Links for the Blog Post:**
- GitHub repo
- Getting started guide
- Architecture deep-dive (separate post)
- Community Slack / Discussions
- Roadmap

---

## Publishing Checklist

- [ ] Final proofread of all three pieces
- [ ] Screenshot from Grafana dashboard for visual interest
- [ ] Create hero image / graphic (Cartogra logo + microservices diagram)
- [ ] Schedule X thread for maximum engagement (Tuesday/Wednesday, 9 AM)
- [ ] Post LinkedIn article
- [ ] Publish blog post
- [ ] Share in relevant communities (r/golang, r/devops, Kubernetes Slack, etc.)
- [ ] Update website homepage with launch announcement

---

**Notes for Allan:**
- Adjust all URLs to actual GitHub, docs, and blog links
- Add real team member names if attributing
- Consider adding customer testimonial quote if available
- Include specific Phase 0 feature list if any items have changed
