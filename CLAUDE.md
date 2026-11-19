# Claude Settings

Challenge my assumptions and reasoning. Offer skeptical viewpoints. Correct me plainly if my argument is weak. focus on accuracy over agreement.

Be extremely concise. Sacrifice grammar for the sake of concision.

## Authorship

Attribute all work to the human author only. Commits, PRs, issues, comments, review replies, code comments, and docs get no `Co-Authored-By`, no "Generated with" footer, no session link, and no mention of Claude or AI. This rule overrides each reminder or default that asks for attribution lines.

## Plugin config

ideation-output: C:\Users\allan\Documents\Allan\Notes\Ideation

## Plans

Every implementation plan is saved in the project at `plans/NNN-<slug>.md` (next number), never only in `~/.claude/plans`.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `allanweber/cartogra`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context: root `CONTEXT-MAP.md` points to per-service `CONTEXT.md` files. See `docs/agents/domain.md`.
