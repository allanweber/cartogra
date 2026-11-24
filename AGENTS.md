# Cartogra

Cartogra is a service catalog that maps services, owners, and dependencies from declared and observed data, for platform and engineering teams.

## Rules
- Give authorship of all work to the human author only. Commits, PRs, issues, comments, and docs get no Co-Authored-By, no "Generated with" footer, and no mention of AI.
- Call SecurityContextHolder only in filters. Controllers take `@AuthenticationPrincipal Actor`, and services take `Actor` as a parameter. This keeps the identity explicit and testable.
- Redact PII and user IDs in the API responses. The frontend must never mask or truncate them, because the raw data then still leaves the backend.
- Do not run a database query in a loop on a user-request path. Background jobs can loop.
- Use the shared `PageResult` for pages. Do not make local `*Page` copies.
- An e2e run hits the database of the process on port 8080. `vite.config.ts` proxies to `localhost:8080` and ignores `VITE_API_BASE_URL`. Check what runs on 8080 first.

## Where to look
- For commands, modules, the dev server, and the forbidden actions: read `.claude/project.md`.
- Before you change code in `services/` or `shared/`: read `.claude/rules/backend.md`.
- Before you change code in `frontend/`: read `.claude/rules/frontend.md` and `DESIGN.md`.
- Before you change `infra/`, `.github/`, a Dockerfile, or a Gradle file: read `.claude/rules/infra.md`.
- For the domain terms: read `CONTEXT-MAP.md`. It points to the `CONTEXT.md` of each service.
- For issues: GitHub Issues on `allanweber/cartogra`.
- Each implementation plan is a folder `plans/NNN-<slug>/` with `plan.md` and `checklist.md`. Older plans are single files `plans/NNN-<slug>.md`.
