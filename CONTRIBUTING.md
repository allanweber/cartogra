# Contributing to Cartogra

## Before you start

Read [AGENTS.md](AGENTS.md) for the full project rules, slash commands, and architecture constraints. It is the authoritative reference for anyone (human or AI) working in this repo.

---

## Branching

- `main` is protected — all changes go through PRs
- Branch naming: `feat/<short-description>`, `fix/<short-description>`, `chore/<short-description>`
- Keep branches short-lived; rebase on `main` before opening a PR

---

## Commit format

This repo uses [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

[optional body]

[optional footer]
```

**Types:** `feat` · `fix` · `chore` · `docs` · `refactor` · `test` · `perf`

**Scope:** service or area affected, e.g. `registry`, `gateway`, `frontend`, `infra`, `ci`

**Examples:**

```
feat(registry): add temporal history endpoint for services
fix(gateway): correct Redis key prefix for rate limit buckets
docs(adr): add ADR-0003 recursive CTEs vs graph DB
test(topology): add blast radius CTE integration test
```

- Subject line: imperative mood, no period, ≤72 chars
- NEVER use `--no-verify` to skip hooks

---

## Opening a pull request

Use the PR template (`.github/PULL_REQUEST_TEMPLATE.md`). Make sure you:

- Link the relevant user story (`USx.y`) and BIP item (`BIPx.z`) if applicable
- Link the ADR if your change involves an architectural decision
- Confirm CI is green before requesting review
- Include a short "how to test" section for non-trivial changes

---

## Code standards

All rules are enforced via [CLAUDE.md](CLAUDE.md) and the files under [.claude/rules/](.claude/rules/):

- **Backend:** Spring Boot 4, Spring Data JDBC (never JPA), constructor injection, `@RestControllerAdvice`, OTel on every service
- **Frontend:** TanStack Start + Router + Query + Table + Forms, shadcn/ui, Zustand, no Redux
- **Infra:** Multi-stage Dockerfiles, non-root user, K8s probes + resource limits, Terraform `for_each`
- **Database:** Flyway migrations only, `tenant_id` on every domain table, soft deletes via `deleted_at`

When in doubt, run `/check-constraints` against the file you're editing.

---

## Gate tooling

Every story's definition of done and every phase gate (`docs/roadmap.md` §2) calls skills that must be re-installable by anyone, not just whoever ran them first. A gate nobody else can run is not a gate — pin the exact source here whenever one of these changes.

| Skill | Where it comes from | Pinned install |
| --- | --- | --- |
| `/code-review`, `/security-review` | Ship with Claude Code | nothing to install |
| `/improve` | Committed at [`.claude/skills/improve/`](.claude/skills/improve/), sourced from `shadcn/improve` | `npx skills add shadcn/improve -a claude-code -y` — integrity pinned by `computedHash` in [`skills-lock.json`](skills-lock.json) |
| `/impeccable`, `/shape` | External CLI: [`impeccable`](https://www.npmjs.com/package/impeccable) on npm | `npx impeccable@3.6.0 install` |
| `/improve-codebase-architecture` | Committed at [`.claude/skills/improve-codebase-architecture/`](.claude/skills/improve-codebase-architecture/), sourced from `mattpocock/skills` | `npx skills add https://github.com/mattpocock/skills/tree/v1.2.3 --skill improve-codebase-architecture -a claude-code -y` — integrity pinned by `computedHash` in [`skills-lock.json`](skills-lock.json) |

Re-running an install command against a newer tag/version is a deliberate act — bump the pin in this table in the same commit, never as a side effect of unrelated work.

---

## Running locally

See [docs/runbooks/local-development.md](docs/runbooks/local-development.md).

---

## Branch Protection

`main` branch protection must be configured in **GitHub → Settings → Branches** before the first external collaborator joins:

| Setting | Required value |
| ------- | -------------- |
| Require a pull request before merging | Enabled |
| Required approving reviews | 1 |
| Dismiss stale reviews on new push | Enabled |
| Required status checks | `build-and-test`, `trivy`, `frontend-ci` |
| Require branches to be up to date | Enabled |
| Restrict force pushes | Disabled for everyone |
| Allow deletions | Disabled |

These settings are not enforced via GitHub Actions — they must be applied manually or via Terraform/`gh` CLI by a repo admin.

---

## Questions

Open a GitHub Discussion or use the architecture discussion issue template for design questions.
