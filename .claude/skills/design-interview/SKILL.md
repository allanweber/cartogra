---
name: design-interview
description: Interview the user relentlessly about a design, one question at a time, until each decision is settled. Use when the user wants to stress-test a design or a plan.
---

Interview the user relentlessly until you have a shared understanding. Map the interview as a **design tree**: each decision branches into the decisions that depend on it.

## The frontier

The **frontier** is each open decision whose prerequisites are settled. You can ask these questions now, without a guess about answers that you do not have yet.

After each answer, compute the frontier again. Settled decisions move the frontier outward and unblock the questions that depend on them.

## Ask one question at a time

Put each frontier question to the user in a separate `AskUserQuestion` call. Wait for the answer before the next call.

- Write the question in two or three short, plain sentences.
- Give one concrete example of the problem. Example: "Monday a user saves v1. Tuesday a second user saves v2 on top of v1. Which version wins?"
- Make your recommended answer the first option, with "(Recommended)" at the end of its label.
- Keep file paths, facts, and trade-offs out of the option text. Write them in the plan.

This rule overrides a skill or a template that tells you to send a batch of questions in one message.

## Facts are your job

Find facts yourself. Do not ask the user for a fact that you can find in the code, the docs, the issue tracker, or a tool. If a fact needs a long search, send a sub-agent with a fresh context. Ask the questions that do not depend on that fact while the sub-agent works.

The decisions belong to the user. Put each decision to the user and wait.

## Mandatory branches

Each design tree has these branches, in addition to the design decisions. Add them at the start, not after the user tells you that they are missing:

- **Business rules**: each rule that the system must enforce, with its edge cases.
- **Behaviour scenarios**: the Given/When/Then cases that prove each business rule.
- **Data model**: the entities, fields, relations, constraints, and migrations.
- **Test seams**: the highest point where the tests touch the system. Prefer existing seams. One seam is ideal.
- **E2E coverage**: which flows need an end-to-end test. Find the e2e framework of the repo before you ask.
- **Acceptance criteria**: checkable criteria for the work.
- **Branch name**: derive it from the branch strategy of the repo (for example `feat/<name>`).
- **Linked issue**: find the issue number in the issue tracker and confirm it.

## Completion

The session is complete when the frontier is empty: you visited each branch of the tree, and you assumed nothing silently.

Then show a short summary of the decisions and ask the user to confirm the shared understanding. Do not act on the decisions before the user confirms.
