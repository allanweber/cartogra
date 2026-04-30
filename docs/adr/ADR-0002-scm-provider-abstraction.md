# ADR-0002 — SCM provider abstraction via SPI

**Date:** 2026-04-30
**Status:** Accepted
**Deciders:** Platform team

---

## Context

Cartogra's Registry service ingests service metadata (repositories, ownership, specs) from source-control systems. The initial target users include teams on **GitHub** and teams on **Azure DevOps Repos** — two providers with materially different APIs, auth models (GitHub App vs Azure DevOps Personal Access Token / Service Principal), and object models (CODEOWNERS vs Required Reviewers / team ownership metadata).

Options considered:

1. Hard-code GitHub-only with Azure DevOps added later as an if-else branch.
2. Design a provider SPI from day one, implement GitHub and Azure DevOps as separate implementations.
3. Use a third-party abstraction library (e.g. Eclipse EGit, JGit) that normalises providers.

## Decision

Introduce a **`ScmProvider` Service Provider Interface** in `services/registry/src/main/java/io/cartogra/registry/infrastructure/scm/`. The interface defines provider-agnostic operations (`listRepositories`, `getFileContents`, `resolveOwnership`, `streamWebhookEvents`). Concrete implementations (`GitHubProvider`, `AzureDevOpsProvider`) are Spring beans registered conditionally via `@ConditionalOnProperty`. The ingestion pipeline is written against the SPI only.

## Consequences

### Positive

- Adding a third SCM provider (GitLab, Bitbucket) requires implementing one interface and one `@Configuration` class — no changes to the ingestion pipeline.
- The SPI makes the domain model SCM-agnostic; tests can inject a stub provider.
- Ownership resolution logic (CODEOWNERS vs Azure DevOps teams) is encapsulated per provider, reducing cross-provider coupling.

### Negative / Trade-offs

- More initial scaffolding than a single implementation.
- Provider-specific configuration bleed (GitHub App private key vs Azure PAT) must be handled carefully to avoid leaking provider details into generic config classes.

### Neutral

- Each provider ships its own Testcontainers-friendly wire-mock test; no shared integration test harness is needed.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| Hard-coded GitHub with future branch | Creates a technical debt trap; refactoring at Provider 2 is costlier than designing SPI upfront |
| Third-party VCS abstraction library | No maintained Java library covers both GitHub Apps and Azure DevOps PAT/SP auth with the required API surface |
| GraphQL federation across providers | Overcomplicated; neither GitHub nor Azure DevOps GraphQL schemas are compatible enough to unify |

## References

- [GitHub Apps documentation](https://docs.github.com/en/apps)
- [Azure DevOps REST API](https://learn.microsoft.com/en-us/rest/api/azure/devops/)
- [project-scope.md §2 — Pillar 1: Living Service Registry](../project-scope.md)
