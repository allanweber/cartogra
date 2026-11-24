# plan.md template

~~~~md
# {Title}
Issue: #{n}  ·  Branch: `{branch}`

## Problem
The problem, from the point of view of the user.

## Solution
The solution, from the point of view of the user.

## User stories
1. As a {actor}, I want {feature}, so that {benefit}.

Write a long list. Cover all aspects of the feature.

## Business rules
- **BR-1**: {One rule that the system must enforce. Include the edge cases.}

## Scenarios
### S-1: {title} (BR-1)
```gherkin
Given {start state}
When {action}
Then {result}
```

Write a minimum of one scenario for each business rule. Include the failure and edge cases, not only the happy path.

## Data model
For each new or changed entity: the fields with types, the keys, the relations, the constraints, the indexes, and the migration. Tell what changes in existing data. A table or a short schema block is correct.

## Decisions
Numbered list of the decisions from the interview: modules, interfaces, API contracts, architecture, and specific interactions. Give the reason when it is not clear.

## Test seams
The seams where the tests touch the system, and the prior art in the repo. Tests examine external behaviour, not implementation details.

## Tasks
### T1: {title}
- Implements: BR-1, S-1
- Module: {one module path, for example `services/contract` or `frontend`}
- Files: {each file that the task creates or changes}
- Depends on: none
- Change: {what to change}
- Test: {the test name and its level: unit, integration, or e2e}
- Done when: {a checkable condition}

## Acceptance criteria
- [ ] {A checkable criterion}

## Out of scope
What this plan does not do.

## Notes
Other facts that the implementer must know.
~~~~

Put code only when a code block tells a decision better than words (a schema, a type, a state machine). Keep it short.
