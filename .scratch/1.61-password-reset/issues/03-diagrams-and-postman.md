Status: ready-for-agent

## Parent

`.scratch/1.61-password-reset/` — Task 1.61: Password reset end-to-end

## What to build

Produce the two PlantUML sequence diagrams required by the workflow.md hard rule (every feature with new use case flows must ship diagrams), and extend the gateway Postman collection with two new requests for the reset endpoints.

**Diagrams** (`docs/diagrams/gateway/`):

- `forgot-password-sequence.puml` — full call chain from browser through `AuthController` → `ForgotPasswordUseCase` → `UserRepository` → PostgreSQL → async `EmailSender (Resend)`; includes the "always 200 — never reveals whether the email exists" invariant as a note.
- `reset-password-sequence.puml` — call chain through `ResetPasswordUseCase` → `UserRepository` → PostgreSQL; alt fragment showing the expired/missing token branch returning `INVALID_OTP` 400, and the success branch returning 200 after nulling out the token columns.

Both diagrams are provided verbatim in the plan at `.claude/plans/plan-tasks-1.61.md` — copy the stubs from there.

**Postman** (`postman/gateway.postman_collection.json`): add two items to the existing `Auth` folder:
- `AC-1 — Forgot Password (always 200)` — `POST {{gateway-url}}/api/auth/forgot-password` with test scripts asserting status 200, envelope shape, `traceId` format, and `X-Trace-Id` header match.
- `AC-3 — Reset Password (valid token)` — `POST {{gateway-url}}/api/auth/reset-password` with the same envelope assertions.

Full request JSON for both items is in the plan file. Note: existing collection requests use `/v1/auth/...`; new requests correctly use `/api/auth/...`. Do not rename the existing requests — that is out of scope.

## Acceptance criteria

- [ ] `docs/diagrams/gateway/forgot-password-sequence.puml` exists, has `@startuml`/`@enduml`, a `title` directive, and renders without syntax errors in a PlantUML validator
- [ ] `docs/diagrams/gateway/reset-password-sequence.puml` exists with the same structural requirements; includes an `alt` fragment for the expired-token branch
- [ ] Both requests appear in the `Auth` folder of `postman/gateway.postman_collection.json`; all four assertions (status, envelope, traceId format, header match) are present in every test script
- [ ] New Postman requests use `{{gateway-url}}/api/auth/...` — not `/v1/auth/...` and not a hardcoded base URL

## Blocked by

None — can start immediately in parallel with issues 01 and 02.
