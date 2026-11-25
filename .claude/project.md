# Project

## Product
- Name: Cartogra
- Public URL: https://cartogra.dev
- Deployed hosts: cartogra.dev (production)

## Modules
Each module of a plan task, and the worker for it.

| Module path | Kind | Workers |
|---|---|---|
| services/*, shared/* | backend | backend-dev, integration-tester |
| frontend | frontend | frontend-dev |
| docs, infra/*, seed/*, docs/api, CONTEXT.md, .claude/rules | other | orchestrator |

## Backend commands
- Rules to read: .claude/rules/backend.md
- Unit test of one class: ./gradlew :<module>:test --tests <Class>
- Compile: ./gradlew :<module>:compileJava :<module>:compileTestJava
- Integration test of one class: ./gradlew :<module>:test --tests <Class>
- Integration test marker: the class extends `Abstract*IT`, or has `@SpringBootTest`. Registry ITs and `*ContractTest` classes start Testcontainers without a base class.

## Frontend commands
Run in: frontend
- Unit test of files: pnpm exec vitest run <files>
- Typecheck: pnpm typecheck
- Lint: pnpm lint
- E2E of one spec: pnpm exec playwright test e2e/<spec>.spec.ts
- E2E folder: e2e/
- Rules to read: .claude/rules/frontend.md, DESIGN.md

## Dev server (for e2e)
- Start: ./gradlew bootRun
- Port: 8080
- Health URL: http://localhost:8080/actuator/health
- Note: uses the dev infra of Docker Compose (the user starts it) and starts no containers

## Final checks
In order:
1. ./gradlew build
2. cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build
3. cd seed/acme-fintech && pnpm typecheck && pnpm test
4. cd frontend && pnpm test:e2e

## Forbidden actions
- Do not run `infra/helm/local-test.sh`. k3d runs are the `k3d-verify` step.
- Do not start containers outside Testcontainers.
- Do not run tests, scripts, the browser, or a guest login against cartogra.dev. It is production.
- Do not install or change anything on the production cluster. Do not use the `~/.kube/cartogra-demo` kubeconfig.

## Build in public
- Roadmap: docs/roadmap.md, bullets under "Worth writing about"
- Drafts folder: docs/bip/
- Format guide: docs/bip/README.md
- Voice examples: docs/bip/3.3-drift-as-a-workflow.md, docs/bip/3.1-otel-spans-to-graph-edges.md
- Images folder: docs/bip/images/
