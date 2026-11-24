# checklist.md template

The checklist is the test record of the plan. The implementer ticks each line when its test passes. Each line refers to an ID in `plan.md`. Do not copy the content of `plan.md`.

```md
# Checklist: {Title}
Plan: [plan.md](./plan.md)

## Tasks
- [ ] T1 {title}: test `{test name}` passes
- [ ] T2 {title}: test `{test name}` passes

## Scenarios
- [ ] S-1 {title}: {unit | integration | e2e} `{test name}`

## Business rules
- [ ] BR-1: scenarios S-1, S-2 pass

## Acceptance criteria
- [ ] AC-1 {short text from plan.md}

## Verification
- [ ] `{full build and test command}` passes
- [ ] `{e2e command}` passes
- [ ] {Other repo checks, for example a local cluster test}
```
