# Contributing to Cartogra

## Before you start

Read [CLAUDE.md](CLAUDE.md) and the files under [.claude/rules/](.claude/rules/) for the full project rules and architecture constraints. They are the authoritative reference for anyone (human or AI) working in this repo.

New here? Pick an issue labelled [`good first issue`](https://github.com/allanweber/cartogra/labels/good%20first%20issue) and comment on it so nobody duplicates the work. For anything bigger, open a [Discussion](https://github.com/allanweber/cartogra/discussions) or a feature request first.

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

Make sure you:

- Link the issue the PR closes (`Closes #123`)
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

---

## Gate tooling

Every story's definition of done and every phase gate (`docs/roadmap.md` §2) calls skills that must be re-installable by anyone, not just whoever ran them first. A gate nobody else can run is not a gate — pin the exact source here whenever one of these changes.

| Skill | Where it comes from | Pinned install |
| --- | --- | --- |
| `/code-review`, `/security-review` | Ship with Claude Code | nothing to install |
| `/improve` | Committed at [`.agents/skills/improve/`](.agents/skills/improve/), sourced from `shadcn/improve` | `npx skills add shadcn/improve -a claude-code -y` — integrity pinned by `computedHash` in [`skills-lock.json`](skills-lock.json) |
| `/impeccable`, `/shape` | External CLI: [`impeccable`](https://www.npmjs.com/package/impeccable) on npm | `npx impeccable@3.6.0 install` |
| `/improve-codebase-architecture` | External: `mattpocock/skills`, tag `v1.2.3`, not committed — run before use | `npx skills add https://github.com/mattpocock/skills/tree/v1.2.3 --skill improve-codebase-architecture -a claude-code -y` |

Re-running an install command against a newer tag/version is a deliberate act — bump the pin in this table in the same commit, never as a side effect of unrelated work.

---

## Running locally

See [docs/runbooks/local-development.md](docs/runbooks/local-development.md).

---

## Branch Protection

`main` is guarded by the **main protection** repository ruleset (GitHub → Settings → Rules):

| Rule | Value |
| ---- | ----- |
| Pull request required | Yes (0 approvals while there is a single maintainer) |
| Force pushes / deletion | Blocked |
| Required status checks | `Gitleaks`, `Backend — Build & Test`, `Frontend — Lint & Test`, `E2E — Playwright`, `Acme seed — Typecheck & Test`, `Container — Build checks` |

Renaming any of those CI jobs breaks the ruleset — update it in the same PR.

---

## License and sign-off

Contributions are licensed under [Apache 2.0](LICENSE) (inbound = outbound). Sign off every commit with `git commit -s` to certify the [Developer Certificate of Origin](https://developercertificate.org/). There is no CLA. See [ADR-0031](docs/adr/0031-apache-2-core-hosted-first-monetization.md).

---

## Questions

Open a [GitHub Discussion](https://github.com/allanweber/cartogra/discussions). Bugs and feature requests go through the [issue templates](https://github.com/allanweber/cartogra/issues/new/choose).
