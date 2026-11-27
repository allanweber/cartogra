# ADR-0034 — SCM webhook setup paths: GitHub manual or App, Azure DevOps manual or opt-in automatic

**Date:** 2026-11-27
**Status:** Accepted
**Deciders:** Allan Weber

---

## Context

Phase 6 makes the catalog follow the SCM through change events, not only through the poll. Each tenant must connect the events of its SCM to Cartogra. Large organizations have different security rules. Some organizations accept a third-party app that can read many repositories. Other organizations block third-party apps and accept only a fine-grained token with the minimum rights.

The providers are different:

- GitHub has organization webhooks. One webhook sends the events of all the repositories of the organization. GitHub also has Apps, which get events with no webhook to make.
- Azure DevOps has no app model and no organization-level hook. A service hook is a subscription in one project for one event type. For example, 40 projects and 3 event types need 120 subscriptions.

## Decision

Each provider has two setup paths:

| Provider | Manual | Automatic |
|---|---|---|
| GitHub | A read-only fine-grained token for reads. The admin makes one organization webhook with the URL and the secret that Cartogra shows. | The Cartogra GitHub App. The admin installs it on all or on selected repositories. Reads use installation tokens that expire after 1 hour. |
| Azure DevOps | The admin makes the service hooks in each project. | Opt-in. The connection credential (PAT or service principal) also gets the right to write service hooks. Cartogra makes, updates, and deletes the subscriptions. |

Cartogra does not offer a GitHub path where a token lets Cartogra make the organization webhook.

On cartogra.dev, one public App serves all the tenants. Cartogra maps each installation id to exactly one tenant. To link an installation, the admin session gets a one-time state value, and a GitHub OAuth step checks that the GitHub user can access the installation. A self-hosted Cartogra registers its own App, because GitHub sends the events of an App to one URL only.

For the manual paths, Cartogra makes the webhook secret, shows it once, and can rotate it.

## Consequences

### Positive

- A strict organization connects with a read-only credential. Cartogra never gets write access to its GitHub organization.
- An organization that accepts apps connects with one click, and Cartogra stores no long-lived GitHub token for it.
- A large Azure DevOps organization does not make many subscriptions by hand.

### Negative / Trade-offs

- GitHub has three auth modes to test: the classic token, the fine-grained token, and the App.
- The App needs a registration, a private key in the production secrets, and an install flow with an OAuth step.
- Self-hosters must register their own App before they can use the automatic GitHub path.
- The automatic Azure DevOps path needs a credential with a write right. A strict organization uses the manual path.

### Neutral

- The App installation is an SCM Connection with the auth mode `app`. Sync jobs, spec paths, and the poll do not change.

## Alternatives Considered

| Option | Reason rejected |
|--------|----------------|
| GitHub token that lets Cartogra make the organization webhook | The token needs write access to the organization webhooks (`admin:org_hook`, or the fine-grained "Webhooks: write" permission). That is more power than the manual path needs, and it saves only one copy-paste. The token also belongs to one person and expires. |
| GitHub App only | Organizations that block third-party apps could not connect with events. |
| Azure DevOps manual only | Too many subscriptions to make by hand in a large organization. New projects would need new hooks by hand. |
| Azure DevOps automatic only | A strict organization could not keep a read-only credential. |
| One private App for each tenant on cartogra.dev | The setup is hard, and Cartogra would store a private key for each tenant. |
| Link an installation with the state value only | The `installation_id` on the setup return can be forged. Without the OAuth check, a tenant could link the installation of another organization. |

## References

- `docs/roadmap.md` Phase 6 (6.3, 6.4, 6.5)
- ADR-0021 (amended): stable repository id as the discovery key
- `services/ingestion/CONTEXT.md`: Setup Path, App Installation
