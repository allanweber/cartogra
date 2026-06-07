Status: ready-for-agent

## Parent

`.scratch/1.61-password-reset/` — Task 1.61: Password reset end-to-end

## What to build

Extract a shared `PasswordEncoder` Spring bean into `SecurityConfig` and constructor-inject it into the three gateway use cases that currently instantiate `BCryptPasswordEncoder` directly inside their constructors. Simultaneously fix the cryptographically weak token generator in `ForgotPasswordUseCaseImpl`, which uses `Math.random()`, replacing it with a `static final SecureRandom` instance.

End state: `SecurityConfig` declares one `@Bean PasswordEncoder`; `LoginUseCaseImpl`, `RegisterUserUseCaseImpl`, and `ResetPasswordUseCaseImpl` each receive it via constructor parameter; `ForgotPasswordUseCaseImpl` generates tokens via `SecureRandom.nextInt(1_000_000)`. The gateway build is green and all existing integration tests pass without modification.

## Acceptance criteria

- [ ] AC-7: `ForgotPasswordUseCaseImpl` generates tokens using `SecureRandom`, not `Math.random()`; the `SecureRandom` instance is `static final`
- [ ] AC-8: `PasswordEncoder` is declared as a `@Bean` in `SecurityConfig` returning `new BCryptPasswordEncoder()`; the field type in all three use cases is the `org.springframework.security.crypto.password.PasswordEncoder` interface, not the concrete class
- [ ] Constructor injection only — no `@Autowired` field injection, no `new BCryptPasswordEncoder()` inside any Spring bean constructor or method body
- [ ] `./gradlew :services:gateway:build` succeeds with zero compilation errors
- [ ] All pre-existing integration tests in `AuthControllerIT` remain green

## Blocked by

None — can start immediately.
