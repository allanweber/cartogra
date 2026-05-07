# Development Workflow Rules

## Checklist-driven branch / PR / issue flow

Every implementation task from `docs/execution-checklist.md` MUST follow this workflow — no exceptions.

### Step-by-step

1. **Create (or reuse) a GitHub issue** for the user story being addressed:
   - Title: `USx.y — <story title>` (e.g. `US0.3 — Registry applies baseline schema`)
   - Body: acceptance criteria from `docs/plan.md`
   - Assign to the correct milestone: `Phase N — <name>`
   - Add to the Cartogra Roadmap project and set the Phase field

2. **Create a feature branch** from `main`:
   - Naming: `feat/<phase>.<seq>-<short-slug>` (e.g. `feat/0.7-shared-contracts`)
   - One branch per checklist task (or tightly coupled group of tasks)

3. **Do the work** on the branch, committing with conventional-commit format.

4. **Open a PR** that:
   - References the issue: include `Closes #<issue-number>` in the PR body
   - Has a short title matching the checklist item
   - Is linked to the same milestone as the issue
   - CI must be green before requesting review

5. **Milestone completion**: A milestone is marked complete only when **all** its user-story issues are closed (either merged PRs or explicit deferral with a public note).

### CLI shortcuts

```bash
# Create issue + assign milestone in one command
gh issue create \
  --title "USx.y — <title>" \
  --body "<acceptance criteria>" \
  --milestone "<Phase N — Name>" \
  --label "user-story"

# Create branch
git checkout -b feat/<phase>.<seq>-<slug>

# Open PR referencing issue
gh pr create \
  --title "<checklist item title>" \
  --body "Closes #<issue>" \
  --milestone "<Phase N — Name>"
```

### Commit and push approval

- NEVER run `git commit` or `git push` (or any destructive git command) without **explicit user approval** first
- Before committing: show the diff summary and the draft commit message, then ask "OK to commit?"
- Before pushing: confirm the target branch and PR intent, then ask "OK to push?"
- This applies to every branch, every phase — no exceptions, even for trivial changes

### Rules summary

- NEVER push directly to `main` — all changes go through a PR
- NEVER open a PR without a linked issue
- NEVER close a milestone manually — it closes automatically when all issues are closed
- A PR that addresses multiple checklist items must still link one primary user-story issue
- NEVER commit or push without explicit user approval
