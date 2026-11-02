# ADR-0031 — Keep Apache-2.0 core; monetize hosted-first with a thin proprietary `ee/`

**Date:** 2026-11-02
**Status:** Accepted
**Deciders:** Allan Weber

---

## Context

The business model is an open-source core plus a paid hosted version and enterprise features. The license decides whether a third party can sell the core as a hosted service.

Facts at decision time:

- The repo is already public under Apache-2.0 (LICENSE since S0.1). Every published commit stays Apache-2.0 forever; relicensing could only affect future code.
- Allan Weber is the sole copyright holder (0 stars, 0 forks, no outside contributors), so relicensing was still legally clean and cost no goodwill.
- Three of the planned "enterprise" features already ship in the core: per-tenant OIDC SSO (`TenantOidcConfigService`), audit events (ADR-0017), and multiple clusters per tenant (`k8s_clusters`).
- Target buyers are enterprise platform teams. Their legal teams often ban AGPL. The launch channels (HN, r/kubernetes, CNCF Slack) are hostile to source-available licenses (BSL/FSL) being sold as "open source".
- The year-one risk is no adoption, not a competitor hosting a project nobody knows yet.

## Decision

- **The core stays Apache-2.0.** No relicensing to AGPL, BSL or FSL.
- **Monetize hosted-first.** Revenue comes from the managed service (operations, upgrades, uptime, data retention), not from license restrictions.
- **A thin proprietary `ee/` layer, added only when the first paid feature is built.** It lives in a top-level `ee/` directory under its own commercial license. The core must build and run without it.
- **What has shipped stays in the core.** Basic OIDC SSO, audit events and the audit timeline, and multiple clusters per tenant stay Apache. They are never moved to `ee/`, because the old versions are forkable anyway and pulling features back reads as a rug-pull.
- **Candidates for `ee/`:** SAML and SCIM provisioning; audit export, retention policies and SIEM streaming; advanced risk scoring; fleet-scale multi-cluster management (cross-cluster views and policies).
- **Contributions use DCO sign-off (`git commit -s`), not a CLA.** This accepts that once outside code lands, relicensing the core needs every contributor's consent, which in practice means never.

## Consequences

### Positive

- Maximum adoption: no legal-review friction for enterprise pilots, and a credible "open source" claim at launch.
- Nothing changes in the repo; the current LICENSE and README stay as they are.
- Stronger portfolio and interview story: a deliberate open-core boundary instead of license gating.

### Negative / Trade-offs

- Anyone, cloud vendors included, may host and sell the core. Accepted: at this stage that would validate demand more than it would hurt revenue.
- The paid moat is thinner, because the features most often gated (SSO, audit) are already free. Revenue depends on hosted convenience and on `ee/` features built later.
- With DCO and no CLA, the relicensing door closes after the first outside contribution.

### Neutral

- Revisit only on a concrete trigger: a third party offering hosted Cartogra commercially and taking pilots. Even then, change the license for future versions only.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| AGPL-3.0 | Many enterprise legal teams ban it, which hits the exact buyer. It only deters cloning, which isn't the year-one risk. |
| FSL-1.1-ALv2 / BSL | Not OSI open source, which undercuts the "trust what you can read" positioning at launch. Past Apache commits stay forkable, so protection is partial. |
| Pull SSO/audit into `ee/` | Shipped Apache versions keep them, so it protects nothing and costs trust. |
| Hosted only, no `ee/` | Simplest, but leaves no lever for the enterprise license sale in the land-and-expand model. |
| CLA instead of DCO | Keeps relicensing possible, but adds contributor friction and signals an intent to relicense, which costs trust for an option that's unlikely to be used. |

## References

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)
- [Functional Source License](https://fsl.software/)
- [Developer Certificate of Origin](https://developercertificate.org/)
- [ADR-0017](ADR-0017-audit-events-ownership-port.md): audit events
