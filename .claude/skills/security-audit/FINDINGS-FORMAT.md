# Format of findings.json

`build_report.py` reads this file and makes the PDF. Write valid JSON. Each text field is plain text, not HTML.

```json
{
  "project": "<Product name from .claude/project.md>",
  "date": "YYYY-MM-DD",
  "scope": "Whole repository at <commit>, or the diff <base>...<branch> of plan NNN",
  "stack": "<languages, frameworks, auth, infra; for example Java 25, Spring Boot 4, PostgreSQL RLS, GitHub Actions>",
  "methodology": [
    {"category": "1", "mapping": "How the category maps to this stack, and what you examined"}
  ],
  "not_applicable": [
    {"category": "5", "reason": "Why the category does not apply"}
  ],
  "findings": [
    {
      "id": "F1",
      "category": "1 | 2 | 3 | 4 | 5 | other",
      "severity": "critical | high | medium | low | info",
      "file": "path/from/repo/root",
      "lines": "42 or 42-47",
      "snippet": "The code at those lines. Mask each secret value: the first 4 characters, then ****",
      "description": "What is wrong",
      "exploitability": "Why and how an attacker can use it",
      "conditions": "Feature flags or configuration that the attack needs. Omit if none",
      "source": "security-auditor | security-review"
    }
  ],
  "strengths": [
    {"category": "3", "text": "What is protected, and how", "evidence": "path:line"}
  ],
  "weaknesses": ["One sentence for each central risk"],
  "recommendations": [
    {"priority": "P1", "text": "The action", "findings": ["F1", "F4"]}
  ],
  "issues": [
    {"n": 1, "title": "[Security] Short description", "labels": ["security", "high"], "body": "Full Markdown of the issue"}
  ]
}
```

## Categories

| ID | Name |
|---|---|
| `1` | Unlocked database (tenant and owner isolation) |
| `2` | Permission decided in the browser |
| `3` | IDOR |
| `4` | Exposed keys (hard-coded secrets) |
| `5` | Unsanitized input (XSS) |
| `other` | A finding of `security-review` that is not in categories 1 to 5 |

## Issue body

Each `body` has these sections, in this order:

1. `## <title>`
2. `Labels: security, <severity>`
3. `### Problem`: what is wrong, and why an attacker can use it.
4. `### Evidence`: `file:line` with the masked code.
5. `### Impact`
6. `### Fix`
7. `### Acceptance criteria`: a checklist of `- [ ]` items that a person can verify.

Put related small findings in one issue. Example: three default secrets in the same configuration files.
