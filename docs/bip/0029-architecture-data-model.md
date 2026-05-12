# BIP 0.29 — Cartogra Architecture Deep Dive: Data Model & Kafka

**Status:** Ready for Publishing (one week after 0.28 launch)  
**Platforms:** X (Twitter), LinkedIn, Blog  
**Audience:** Architecture enthusiasts, database designers, async/event-driven advocates  

---

## 📌 X Thread (6 tweets)

**Tweet 1 — Hook**
```
Let's talk architecture. 🧵

Yesterday we launched Cartogra. Today I want to dive into some choices 
we made that might help you think about your own systems.

The data model. Kafka events. Why we chose what we chose.

[6 tweets]
```

**Tweet 2 — The Data Model**
```
Cartogra's domain model is simple:

Tenants → Teams → Users → Services

Each service belongs to a team. Teams belong to tenants. 
Every table has tenant_id. Every query filters by tenant_id.

Why? Multi-tenancy isn't bolted on. It's in the DNA. 
RLS policies at the database level mean even a bug can't leak data.
```

**Tweet 3 — UUIDs & Immutability**
```
Every ID is a UUID. Every timestamp is TIMESTAMPTZ.
Hard deletes don't exist. Only soft deletes with deleted_at.

Why UUID? 
- No sequential guessing attacks
- Distributed generation (no coordination)
- Safe to replicate

Why soft delete?
- Audit trail
- Accident recovery
- Legal holds
- Analytics over historical data
```

**Tweet 4 — JSONB for Flexibility**
```
Metadata and flexible fields? JSONB + GIN indices.

```yaml
services table:
  id: UUID (PK)
  tenant_id: UUID (fk + index)
  name: TEXT
  team_id: UUID (fk)
  metadata: JSONB  ← can hold tags, custom fields, labels
  created_at: TIMESTAMPTZ
  updated_at: TIMESTAMPTZ
  deleted_at: TIMESTAMPTZ (soft delete)
```

One schema. Infinite extensibility. Type safety where it matters.
Flexibility where it doesn't.
```

**Tweet 5 — Kafka Events**
```
Every state change is an event:

```json
{
  event_id: UUID,
  event_type: "service.registered",
  entity_id: UUID,
  tenant_id: UUID,
  timestamp: ISO8601,
  version: 1,
  correlation_id: UUID,
  payload: {...}
}
```

Message key = entity_id (ensures ordering per service)
Topic = cartogra.registry.service.registered

One event = one source of truth. No split-brain.
Consumers are decoupled. New features add new consumers, not migrations.
```

**Tweet 6 — The Benefit**
```
This design paid dividends:

✅ Audit trail: every state change is logged
✅ Scale: services can consume events independently
✅ Recovery: replay events from any point
✅ Debugging: trace requests through the event chain
✅ Governance: RLS prevents bugs from becoming breaches

The cost: think harder upfront.
The payoff: sleep better at night.
```

---

## 🔗 LinkedIn Post

**Headline:**
"Cartogra's Architecture: Building a Multi-Tenant Registry That Scales Safely"

**Body:**

When you design a service registry, you're making bets about the future:
- How many tenants? (Start with many, build isolation from day one)
- How fast will data change? (Faster than you think—use events)
- How will you prevent bugs? (Database constraints, not just application logic)

We made specific choices in Cartogra's architecture. Not because they're novel, but because they work.

**The Data Model**

Every table has `tenant_id`. Every query filters by `tenant_id`. Every index includes `tenant_id`.

It sounds boring. It's incredibly powerful.

Why? Because multi-tenancy isn't a feature you add later. It's a property you build in. Row-level security policies sit at the database layer—a safety net that catches even application bugs.

Every ID is a UUID. Why not auto-increment? Because you can't generate UUIDs from a single database, and in distributed systems, you generate them everywhere. UUIDs scale.

Timestamps are TIMESTAMPTZ (with timezone info). Never TIMESTAMP. Time without timezone is a bug waiting to happen.

Hard deletes don't exist. Only `deleted_at TIMESTAMPTZ`. Why? Audit trails, accident recovery, compliance, and historical analytics. A soft delete costs you one column and immeasurably simplifies your life two years from now.

**Kafka for Event Sourcing**

Every state change is an event:

- Service registers? Event.
- API spec changes? Event.
- Team ownership transfers? Event.
- Service goes down? Event.

Events are immutable. Ordered per entity (message key = entity_id ensures Kafka assigns all updates to the same partition for that service). Never replayed incorrectly.

Why event-driven instead of synchronous?
- **Decoupling**: The registry doesn't care who listens. Topology builder, contract validator, notification system—all independent consumers.
- **Auditability**: Every change is documented in Kafka.
- **Replayability**: New feature? New consumer reads the event log from the start.
- **Resilience**: If a consumer crashes, it resumes where it left off.

**Structured Logging & Tracing**

Every request gets a 32-character hex trace ID. Propagated through:
- HTTP headers (`traceparent`)
- Kafka message headers
- Structured JSON logs (MDC)

Why? Because debugging across services shouldn't be a nightmare. You hit `/api/services` on the frontend. It calls the gateway. Gateway calls the registry service. Registry queries the database. If something fails, you trace it: frontend logs → gateway logs → registry logs → database logs. All same trace ID.

**Index Strategy**

```sql
-- Single-table indices
CREATE INDEX ON services (tenant_id, deleted_at);  -- filter + soft delete
CREATE INDEX ON services USING GIN (metadata);      -- JSONB flexibility
CREATE INDEX ON services (team_id, tenant_id);      -- foreign key + tenancy

-- Unique constraints
ALTER TABLE services ADD CONSTRAINT 
  unique_service_per_tenant UNIQUE (tenant_id, name);
```

Indices are boring. They're also what separates "scales fine" from "melts down at scale."

**What We Didn't Do**

- No sequence tables. UUIDs everywhere.
- No stored procedures. Business logic lives in the application.
- No denormalization. Let PostgreSQL handle the JOIN.
- No replication to a separate analytics database. Events are the source of truth.

**Why This Matters**

Good architecture isn't about using the fanciest tech. It's about making the right boring choices early.

Cartogra's data model will scale to thousands of tenants, millions of services. Not because we're geniuses, but because we applied boring lessons from a decade of distributed systems:

- **Single source of truth** (Kafka events)
- **Immutability** (soft deletes)
- **Isolation** (tenant_id everywhere)
- **Observability** (trace IDs, structured logs)
- **Resilience** (decoupled consumers)

### Design Trade-offs

**Pro:**
- Type safety (PostgreSQL + Java 25 records)
- Audit trail (soft deletes + events)
- Multi-tenancy (RLS policies)
- Observability (trace correlation)

**Con:**
- Slightly higher storage (soft deletes keep old rows)
- Event ordering discipline (must use Kafka consistently)
- RLS adds query complexity (minimal, but not zero)

Worth it? For a system that will be mission-critical to thousands of teams? Absolutely.

### Getting Started

If you're designing a multi-tenant system, steal these patterns:
1. Add `tenant_id` to every table
2. Create RLS policies at the database layer
3. Use events as your source of truth
4. Propagate trace IDs everywhere
5. Structure your logs as JSON

Not every system needs events. Not every system needs multi-tenancy. But if you're building either, start with the hard problems solved.

Cartogra is open source. The code is on GitHub, the data model in the migrations.

Come debug it with us.

---

## 📰 Blog Post (1200–1800 words)

**Title:** "How We Built Cartogra's Data Model to Scale Safely: Architecture Patterns for Multi-Tenant Registries"

**Excerpt:**
```
When you ship a service registry to thousands of teams, you can't afford 
architectural debt. We made intentional choices about data storage, event 
architecture, and observability. Here's why—and how you can apply them.
```

### The Challenge

Multi-tenant systems are hard. Registry systems are hard. Multi-tenant registry systems are **very** hard.

You need:
- Complete isolation between customers (no data leaks)
- High consistency (customers must trust the data)
- Audit trails (compliance teams will ask)
- Fast queries (UX matters)
- Easy scaling (anticipate growth)

None of these are novel requirements. But getting all of them right requires discipline.

### Pattern 1: Tenant ID Everywhere

The first rule of multi-tenancy: **tenant_id on every table. Every index. Every query.**

```sql
CREATE TABLE services (
    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name TEXT NOT NULL,
    team_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT unique_service_per_tenant UNIQUE (tenant_id, name),
    CONSTRAINT fk_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_team FOREIGN KEY (team_id) REFERENCES teams(id)
);

CREATE INDEX idx_services_tenant ON services (tenant_id, deleted_at);
CREATE INDEX idx_services_team ON services (team_id, tenant_id);
```

**Why this pattern?**
- Every query includes `WHERE tenant_id = ?`
- Database can enforce isolation at query time
- No sneaky bugs that expose Customer A's data to Customer B
- Indexes are optimized for the common case

**The catch?** You can never forget the `tenant_id` filter. Ever. A single query without it is a breach.

That's where the next pattern comes in.

### Pattern 2: Row-Level Security at the Database

PostgreSQL's RLS (Row-Level Security) is an insurance policy for developer mistakes.

```sql
ALTER TABLE services ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON services
    USING (tenant_id = current_setting('app.tenant_id')::UUID);

CREATE POLICY tenant_isolation_modify ON services
    FOR INSERT WITH CHECK (tenant_id = current_setting('app.tenant_id')::UUID);
```

How it works:
1. Spring application sets a session variable: `SET app.tenant_id = '...'`
2. PostgreSQL checks **every query** against the RLS policy
3. If a query tries to access a row where `tenant_id` doesn't match, it fails
4. Even bugs in your application can't leak data

**Cost:** ~1–5% query overhead (worth it)  
**Benefit:** Sleep well at night knowing isolation is guaranteed

### Pattern 3: UUIDs for Distributed Safety

Every ID is a UUID (Universally Unique Identifier), not an auto-increment integer.

Why?

**Auto-increment integers are a liability:**
- Sequential IDs leak information ("we had X users")
- You can't generate them without a central database
- In distributed systems, you need coordination
- Sharding becomes a nightmare

**UUIDs solve this:**
- No central generator needed
- Every service generates them independently
- Safe to replicate across regions
- No information leakage

```java
// In your entity
@Id
private UUID id = UUID.randomUUID();

// Or in the database
id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY
```

**Trade-off:** UUIDs are 16 bytes vs. 8 bytes for a bigint. In 2026, that's basically free.

### Pattern 4: TIMESTAMPTZ for All Timestamps

**Never use TIMESTAMP without timezone.** Ever.

```sql
-- ❌ Wrong
created_at TIMESTAMP NOT NULL DEFAULT now()

-- ✅ Right
created_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

Why? Because time without timezone context is meaningless.

Your service is in UTC. Your CDN is in UTC. Your database is in UTC. But when you query that `created_at` value from a client in Tokyo, what time is it really?

TIMESTAMPTZ stores the actual moment in time (UTC internally) and converts to the client's timezone on display. No confusion.

### Pattern 5: Soft Deletes, Not Hard Deletes

Never actually delete rows. Just mark them deleted.

```sql
ALTER TABLE services ADD COLUMN deleted_at TIMESTAMPTZ;

-- To "delete" a service
UPDATE services SET deleted_at = now() WHERE id = ? AND tenant_id = ?;

-- To query active services
SELECT * FROM services WHERE tenant_id = ? AND deleted_at IS NULL;

-- Index for filter performance
CREATE INDEX idx_services_not_deleted ON services (tenant_id) 
  WHERE deleted_at IS NULL;
```

Why?

- **Audit trail:** You know exactly when and what was deleted
- **Recovery:** Accident? Restore the row: `UPDATE services SET deleted_at = NULL`
- **Compliance:** Keep historical data for audits
- **Analytics:** "How many services existed in Q1 2026?"

The extra column costs almost nothing. The insurance is invaluable.

### Pattern 6: JSONB for Flexible Metadata

Your schema is fixed. Your needs will change.

Use JSONB for metadata:

```sql
CREATE TABLE services (
    -- ... standard columns ...
    metadata JSONB,
    
    -- Index for fast queries
    CONSTRAINT chk_metadata_is_object 
      CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX idx_services_metadata 
  ON services USING GIN (metadata);

-- Query examples
SELECT * FROM services 
  WHERE metadata->>'environment' = 'production';

SELECT * FROM services 
  WHERE metadata->'tags' ? 'critical';
```

**Trade-off:** Type flexibility vs. type safety. Use JSONB for truly optional, evolving fields. Keep strong types for your domain model.

### Pattern 7: Event Sourcing with Kafka

Every state change in the system is an event. Events live in Kafka.

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "event_type": "service.registered",
  "entity_id": "b60c1f35-df68-4e3a-8c68-84e6b5fc9dca",
  "tenant_id": "5f8c8f8c-8c8c-8c8c-8c8c-8c8c8c8c8c8c",
  "timestamp": "2026-05-07T14:32:10Z",
  "version": 1,
  "correlation_id": "abc123def456",
  "payload": {
    "name": "payment-service",
    "team_id": "...",
    "apiSpec": {...}
  }
}
```

**Why events?**

- **Source of truth:** Events are immutable. State is derived.
- **Audit trail:** Who did what, when, in what order?
- **Decoupling:** Registry publishes events. Topology, contract validator, notifier—all independent subscribers.
- **Replayability:** New feature? Spin up a new consumer to process the event log from the start.
- **Resilience:** Consumer crashes? Resume from the last offset.

**Message key:** `entity_id` (ensures all updates to the same service go to the same partition → ordered consumption)

**Topic naming:** `cartogra.{domain}.{entity}.{event}` (e.g., `cartogra.registry.service.registered`)

### Pattern 8: Trace ID Propagation

Every request has a trace ID. That trace ID is propagated through every layer.

```
Client Request
    ↓ (trace: abc123...)
Gateway (sets X-Trace-Id response header)
    ↓ (trace: abc123...)
Registry Service (logs: "traceId: abc123...")
    ↓ (trace: abc123...)
PostgreSQL (logs: "traceId: abc123...")
```

Implementation:
1. **HTTP**: Client → Gateway (response header `X-Trace-Id`)
2. **Kafka**: Producer sends trace ID in message headers → Consumer reads and uses
3. **Logs**: All JSON logs include `"traceId": "abc123..."`
4. **Database**: If possible, log trace ID in query context

Tools like OpenTelemetry do this automatically. Use them.

### Putting It Together

Here's a typical flow:

```
1. Client POSTs /api/v1/services
   ↓
2. Gateway assigns trace_id, forwards to registry service
   ↓
3. Registry creates service record (tenant_id + other fields)
   ↓
4. Registry publishes Kafka event: service.registered
   ↓
5. Topology service consumes event, updates graph
6. Notifier service consumes event, sends alerts
7. All log with the same trace_id
   ↓
8. Response: 201 + X-Trace-Id header
```

Every step is traceable. Every step is durable. Every step is documented.

### When NOT to Follow These Patterns

- **Small startup with one tenant?** You don't need RLS yet. Add it when you scale.
- **Reporting-heavy system?** JSONB might be too flexible. Consider a data warehouse.
- **Real-time analytics?** Events are great for this. Go all-in.
- **Highly transactional (banking)?** Events can add latency. Measure.

### Performance Implications

These patterns add overhead:
- **RLS policies:** ~1–5% query latency
- **JSONB indices:** ~10% more storage
- **Event sourcing:** Slight write latency (publish to Kafka)

Is it worth it? For a system you'll run for 5+ years, storing data that can't be leaked and can't be lost? Absolutely.

### Conclusion

Cartogra's architecture isn't revolutionary. It's **boring and proven**.

- UUIDs, not sequences
- TIMESTAMPTZ, not TIMESTAMP
- Soft deletes, not hard
- RLS policies, not application checks
- Events, not stored procedures
- Trace IDs everywhere

These choices compound over time. Two years from now, they'll save you from a data breach, an impossible bug, or a compliance audit.

The code is open source on GitHub. Read the migrations. See how these patterns applied. Steal them for your systems.

---

**Links for the Blog Post:**
- [Link to Cartogra GitHub migrations](docs/architecture/data-model.md)
- [Link to Kafka topics documentation](docs/architecture/kafka-topics.md)
- [PostgreSQL RLS documentation](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [UUID vs. auto-increment debate](https://news.ycombinator.com/search?query=uuid+primary+key)
- [Event sourcing patterns](https://martinfowler.com/eaaDev/EventSourcing.html)

---

## Publishing Checklist

- [ ] Create diagram: Data model (tenant → team → user → service hierarchy)
- [ ] Create diagram: Event flow (registry → Kafka → topology, contracts, notifier)
- [ ] Create diagram: Trace ID propagation (HTTP → service → DB)
- [ ] Screenshot of actual migrations from GitHub
- [ ] Proofread for technical accuracy
- [ ] Schedule X thread (3–5 days after 0.28 launch)
- [ ] Post LinkedIn article
- [ ] Publish blog post
- [ ] Link from main Cartogra docs

---

**Notes for Allan:**

- Adjust all GitHub links to actual repository paths
- If data model has changed, update the SQL examples
- Add specific numbers if available (e.g., "query overhead measured at X%")
- Consider adding a "we learned this the hard way" anecdote if applicable
- Include comparison table: "These patterns vs. alternative approaches"
