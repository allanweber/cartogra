---
name: security-audit
description: Audit the code for tenant isolation, browser-only permissions, IDOR, hard-coded secrets, and XSS with an independent agent, run the security-review skill, and make a PDF report with GitHub issue texts.
disable-model-invocation: true
---

Arguments:

| Call | Scope |
|---|---|
| `/security-audit` | The whole repository, at the checked-out commit |
| `/security-audit 034` | The diff of the branch of plan 034, from `origin/main` |

The audit has two parts:

- **Category audit:** the `security-auditor` agent audits five categories and writes `findings.json`.
- **Change review:** the `security-review` skill reviews the changes on the branch.

You start both parts, merge the results, and make the PDF. Do not audit the code yourself, so that the audit stays independent of the work in this session.

## 1. Prepare

1. If a plan number is given:
   1. Find `plans/<NNN>-<slug>/` in the worktrees of `git worktree list`. If it does not exist, stop and tell the user.
   2. Get the branch from the header of `plan.md`, and find its worktree. If the branch has no worktree, use the main checkout and tell the user which branch is checked out.
   3. Use `origin/main` as the base ref, unless the user gives a different base.
2. Set the output folder: `docs/security-audit/<YYYY-MM-DD>/`, or `docs/security-audit/<YYYY-MM-DD>-<NNN>/` for a plan.

## 2. Start the category audit

Send the `security-auditor` agent with the Agent tool. Give it the scope, the worktree path, the base ref (for a plan), and the path `<output folder>/findings.json`. It runs in the background.

## 3. Run the change review

While the agent runs, call the Skill tool with `security-review`, and obey it in the worktree.

- If the branch has no changes from `origin/main`, skip this step. Record the reason for the report.
- Keep each finding: the file, the lines, the description, the exploit, and the severity.

## 4. Merge

When the agent returns:

1. Read `findings.json`.
2. Add each `security-review` finding with `source: "security-review"`. Use category 1 to 5 when it fits. Else, use `other`.
3. If both parts found the same problem at the same `file:line`, keep one finding, and set `source` to `security-auditor, security-review`.
4. Add an issue for each new actionable finding. Add a recommendation for it.
5. Write `findings.json` again.

## 5. Make the PDF

1. Run `python3 -I .claude/skills/security-audit/build_report.py <output folder>/findings.json <output folder>/security-audit-report.pdf`. It uses the Python standard library and headless Chrome. It installs nothing.
2. Run `pdfinfo` on the PDF, and get the number of pages.
3. Rasterize the pages with `pdftoppm -r 60 -png`. Look at each page with the Read tool.
4. If a chart does not show, or a table is not readable, correct `build_report.py` or the data. Then make the PDF again.

## Result

Give the user:

- The path of the PDF.
- The findings, file by file and line by line: the severity, `file:line`, and one sentence.
- The paths of all the files that you made: `findings.json`, the HTML file, and the PDF.

Then stop. Do not create GitHub issues, and do not fix the findings. The user decides the next step.

Do not commit the files in `docs/security-audit/`. They describe weaknesses that are not fixed. Git ignores that folder.
