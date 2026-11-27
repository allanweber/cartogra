# Review record: notifications

Branch: `feat/5.7-notifications`. Base ref: `origin/main`. Date: 2026-11-26.

## Security findings

| Severity | Finding | Action |
|---|---|---|
| High | A saved webhook could point at an internal host. A test then sent a request from the service network and exposed the status to the caller. | Fixed. Save-time validation rejects internal host names, private IP literals, and user info. Remote hosts must use `https`. At send time, Contract resolves the host and refuses loopback, link-local, site-local, any-local, multicast, and IPv6 unique-local addresses. This also blocks wildcard DNS names that point at loopback. `http` on `localhost` and `127.0.0.1` needs the `contract.notifications.allow-local-webhooks` setting, which is true only for integration tests and local development. |
| High (residual) | The send-time check does not pin the address. A DNS name can change its answer between the check and the POST. | Accepted risk. Compensating controls: the same blocked address classes apply at save time and at send time; local destinations are off by default in production; a test is an explicit write-role action; retries are bounded; every try is logged with its status. Pinning would require a custom TLS name check, which adds a larger risk than this residual window. |
| Medium | One rule can hold 22 destinations. A test makes up to three calls per destination, and a check can start delivery for two rules. | Accepted risk. Compensating controls: only ADMIN, MEMBER, and TEAM_OWNER can write or test; each rule holds at most 22 destinations; each send makes at most three attempts; the HTTP client uses a 3-second connect timeout and a 5-second read timeout; retries occur only for 429, 5xx, and network errors; a test is an explicit user action; every attempt is logged. |
| Low | `create` stores a `serviceId` without checking that the Service exists in the tenant. | Accepted risk. Compensating controls: every rule query is tenant-scoped; the Service id only filters a rule inside the caller's tenant; no message crosses tenants; the id is not user data and reveals no other tenant's data. |
| Informational | No hard-coded credential was found. Repositories use named parameters. Both new tables have tenant filters and forced RLS. The unique index enforces one live rule per scope. Rule writes require a write role. The frontend renders masked values without reconstruction. No client-side XSS sink was found. | No action. |

## Correctness and standards findings

- The toggle audit event now records the new enabled state, as BR-6 requires.
- Both senders now mark 4xx responses as failed, as BR-12 requires.
- The test endpoint now returns only the rows written by that test, as S-19 requires.
- The patch path now uses `NotificationRuleRequest` in `api/dto`, as the one-request-record rule requires.
- `listAttempts` now follows the verb-first naming rule.
- Attempt and rule badges now use the outline variant and the status color tokens, as `DESIGN.md` requires.
