# ADR-0033 — GitHub Action: source in this repo, Marketplace copy synced by tag

**Date:** 2026-11-25
**Status:** Accepted
**Deciders:** Allan Weber

---

## Context

Story 5.6 adds the GitHub Action "Cartogra Contract Check". It calls `POST /api/v1/contracts/check` and fails a pull request on a breaking change. The GitHub Marketplace lists an action only when the action is in the root of its own public repository, with one `action.yml`. This repository is a monorepo, so the Action cannot be listed from here.

The Action must change together with the API that it calls. Its tests run in the CI of this repository. A second repository with its own source would drift from the API, and it would need its own review process.

## Decision

- The **source** of the Action is `ci-extensions/github-action/` in `allanweber/cartogra`. All changes and issues go to this repository.
- The **Marketplace copy** is the public repository `allanweber/cartogra-contract-check`. Nobody edits it by hand.
- The tag `action-vX.Y.Z` on this repository starts `.github/workflows/action-release.yml`. The workflow:
  1. Fails when the tag version is not equal to `version` in `ci-extensions/github-action/package.json`, or when `node --test` fails.
  2. Fails, and changes nothing, when the tag `vX.Y.Z` exists already in the copy.
  3. Replaces the content of the copy with the folder, commits, pushes the tag `vX.Y.Z`, force-moves the tag `vX`, and creates the GitHub Release `vX.Y.Z`.
- The workflow uses one secret, `ACTION_REPO_TOKEN`: a fine-grained PAT with `contents: write` on `cartogra-contract-check` only. In this repository the workflow has `permissions: contents: read`.
- The tag `vX.Y.Z` is never moved. Only the major tag `vX` moves.
- The Action has no dependencies (Node 24, built-in `fetch`), so the copy has no `node_modules`, no bundle, and no `dist/`.

## Consequences

### Positive

- One source, tested in the same CI as the API that it calls.
- Users get a Marketplace listing and a short reference: `allanweber/cartogra-contract-check@v1`.
- A release is one tag. The copy cannot drift by hand, because each release replaces it.

### Negative / Trade-offs

- A second repository and a PAT to keep. If the PAT expires, a release fails until the maintainer replaces the secret.
- Issues opened on the copy must go to this repository by hand. The README of the copy tells users to open issues here.
- The first Marketplace publication is a manual step in the GitHub UI.

### Neutral

- Users can also use the source directly: `allanweber/cartogra/ci-extensions/github-action@<ref>`, for example to test a branch.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| Only the monorepo reference (`allanweber/cartogra/ci-extensions/github-action@<ref>`) | No Marketplace listing, a long reference, and the tags of the monorepo do not give a stable `v1` for the Action. |
| Only a separate repository with the source | The Action drifts from the API. Its tests do not run in the CI of the API. |
| A git subtree push or a submodule | More steps for the maintainer, and the history of the monorepo leaks into the copy. A content copy per release is simpler. |
| A deploy key in place of a PAT | A deploy key cannot create a GitHub Release. The release needs the API. |

## References

- Plan `plans/035-github-action/plan.md`
- [ADR-0031](0031-apache-2-core-hosted-first-monetization.md): the copy has the Apache-2.0 license of the core.
- `ci-extensions/github-action/README.md`
