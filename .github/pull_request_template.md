## What changed

<!-- One or two sentences. -->

## Why

Closes #

<!-- Architectural change? Link the ADR: docs/adr/NNNN-....md -->

## Scope

- [ ] `services/gateway`
- [ ] `services/registry`
- [ ] `services/ingestion`
- [ ] `services/topology`
- [ ] `shared/*`
- [ ] `frontend`
- [ ] `seed/acme-fintech`
- [ ] `infra/*` / CI

## How verified

<!-- List the commands you ran. Delete the ones that do not apply. -->

- Backend: `./gradlew :services/<name>:build` (or `./gradlew build` for everything)
- Frontend: `cd frontend && pnpm lint && pnpm typecheck && pnpm test`
- Seed: `cd seed/acme-fintech && pnpm typecheck && pnpm test`
- E2E: `cd frontend && pnpm run test:e2e` against the `infra/docker-compose/docker-compose.yml` stack

Docs-only changes skip E2E in CI.

## Checklist

- [ ] Commits follow Conventional Commits (`feat(scope): ...`) and are signed off with `git commit -s` (DCO, Apache 2.0)
- [ ] No hooks skipped (`--no-verify`)
- [ ] New Flyway migration added instead of editing a committed one
- [ ] ADR added/updated if this involves an architectural decision
- [ ] Required CI checks green: Gitleaks, Backend — Build & Test, Frontend — Lint & Test, E2E — Playwright, Acme seed — Typecheck & Test, Container — Build checks
- [ ] Gate tool pins in `CONTRIBUTING.md` updated in this PR if a skill source/version changed
