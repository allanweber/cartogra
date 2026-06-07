Status: ready-for-agent

## Parent

`.scratch/1.61-password-reset/` — Task 1.61: Password reset end-to-end

## What to build

Add three integration tests to `AuthControllerIT` that exercise the password reset flow end-to-end through the HTTP layer: a happy path, an expired token rejection, and a rate limit enforcement check.

Test setup uses `NamedParameterJdbcTemplate` injected into the IT class to insert tenant and user rows directly — no API-driven registration. Assertions stay in the HTTP layer only; the DB is read mid-test solely to retrieve the generated reset token for use in a subsequent request.

**Happy path** (AC-1, AC-3): insert a verified `LOCAL` user → `POST /api/auth/forgot-password` → read `password_reset_token` from DB → `POST /api/auth/reset-password` → `POST /api/auth/login` with the new password, expect 200 with `data.accessToken`.

**Expired token** (AC-4): insert a verified user with `password_reset_token = '999999'` and `password_reset_token_exp = now() - interval '1 hour'` → `POST /api/auth/reset-password` with that token, expect 400 with a non-empty `error.code`.

**Rate limit** (AC-6): fire 11 `POST /api/auth/forgot-password` requests in a loop (no DB setup needed — endpoint always 200 for unknown emails); assert the 11th response is 429 with a `Retry-After` header.

The `@BeforeEach` in `AbstractGatewayIT` already flushes `rate_limit:*` Redis keys — no additional teardown is needed in the rate-limit test.

## Acceptance criteria

- [ ] AC-1: `POST /api/auth/forgot-password` with a verified user returns 200; response envelope contains `traceId` matching `/^[0-9a-f]{32}$/`; `X-Trace-Id` header equals body `traceId`
- [ ] AC-3: `POST /api/auth/reset-password` with a valid token returns 200; `POST /api/auth/login` with the new password subsequently returns 200 with `data.accessToken` present
- [ ] AC-4: `POST /api/auth/reset-password` with an expired token returns 400 with `error.code` non-empty and `traceId` present
- [ ] AC-6: The 11th `POST /api/auth/forgot-password` from the same IP within a burst returns 429 with a `Retry-After` header
- [ ] All three new ITs are green in CI (`./gradlew :services:gateway:test`)

## Blocked by

`.scratch/1.61-password-reset/issues/01-passwordencoder-extraction-and-secure-random.md` — the `PasswordEncoder` bean must be wired before the container can start cleanly in the IT context.
