# Postman Rules

## Collection schema

- Schema: Postman v2.1.0 — `info` block with `schema` URL, top-level `item` array for folders, `variable` array for service-specific base URL only (e.g. `gateway-url`).
- All shared variables (`authToken`, `tenantId`, etc.) live in `postman/cartogra-local.postman_environment.json` — never duplicate them in the collection.

## Request item rules

- **Name**: `AC-{N} — {AC title}`
- **URL**: use `{{<service>-url}}` (e.g. `{{gateway-url}}`) — never hardcode base URLs.
- **Auth/tenant**: use `{{authToken}}`, `{{tenantId}}` from the environment.
- **ID capture**: when an AC creates a resource that subsequent ACs depend on, capture the ID:
  ```js
  pm.environment.set("serviceId", pm.response.json().data.id);
  ```
- **Every test script must assert**:
  1. Correct HTTP status
  2. `res.body.data` present (enveloped endpoints)
  3. `res.body.traceId` matches `/^[0-9a-f]{32}$/`
  4. `x-trace-id` header equals `res.body.traceId`

## Internal-only tasks

If the task has no external HTTP surface: write "No Postman requests — verified by integration tests."
