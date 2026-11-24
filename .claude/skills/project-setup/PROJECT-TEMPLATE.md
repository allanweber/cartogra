# Project file template

Each project that uses these skills has one file `.claude/project.md`. The skills and the agents read their project facts from this file only. Run `/project-setup` to fill it from the code and from questions. The block below is its format.

If a section does not apply to the project, write `none` under it. If a skill needs a value that is missing, it stops and asks the user.

~~~~md
# Project

## Product
- Name: {product name, for example "Cartogra"}
- Public URL: {https://example.dev, or none}
- Deployed hosts: {each host that agents must never call, or none}

## Modules
Each module of a plan task, and the worker for it.

| Module path | Kind | Workers |
|---|---|---|
| {services/<name>, or a glob like services/*} | backend | backend-dev, integration-tester |
| {frontend} | frontend | frontend-dev |
| {docs, infra, seed/*} | other | orchestrator |

## Worktrees
- Folder: {.claude/worktrees/<id>}
- Setup: {commands to run after the worktree is created, for example `pnpm install` in `frontend`}

## Backend commands
- Rules to read: {.claude/rules/backend.md, or none}
- Unit test of one class: {./gradlew :<module>:test --tests <Class>}
- Compile: {./gradlew :<module>:compileJava :<module>:compileTestJava}
- Integration test of one class: {./gradlew :<module>:test --tests <Class>}
- Integration test marker: {how to know that a test starts containers, for example "extends Abstract*IT"}

## Frontend commands
Run in: {frontend}
- Unit test of files: {pnpm exec vitest run <files>}
- Typecheck: {pnpm typecheck}
- Lint: {pnpm lint}
- E2E of one spec: {pnpm exec playwright test e2e/<spec>.spec.ts}
- E2E folder: {e2e/}
- Rules to read: {.claude/rules/frontend.md, DESIGN.md, or none}

## Dev server (for e2e)
- Start: {./gradlew bootRun}
- Port: {8080}
- Health URL: {http://localhost:8080/actuator/health}
- Note: {for example "uses the dev infra of Docker Compose and starts no containers"}

## Final checks
In order, in the worktree:
1. {./gradlew build}
2. {cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build}
3. {cd frontend && pnpm test:e2e}

## Forbidden actions
- {for example "Do not run infra/helm/local-test.sh"; "Do not start containers outside Testcontainers"}

## Build in public
- Roadmap: {docs/roadmap.md, and the heading of the bullets to write about, for example "Worth writing about"}
- Drafts folder: {docs/bip/}
- Format guide: {docs/bip/README.md}
- Voice examples: {two published drafts, or none}
- Images folder: {docs/bip/images/}
~~~~
