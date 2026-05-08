# Cartogra — Build in Public (BIP) Format Guide

This file is the canonical reference for all BIP artifacts. Every BIP planning session must cover all applicable channels.

---

## Standard Output Channels

| Channel | Format | Length | Notes |
|---------|--------|--------|-------|
| Blog post | Long-form article | 600–1000 words | Published to personal blog or dev.to |
| Twitter/X thread | Threaded tweets | 4–8 tweets | First tweet = hook; thread tells the story |
| Instagram carousel | Slide deck + caption | 5–8 slides + 150–200 word caption | Slide 1 = hook visual; last slide = CTA |
| LinkedIn article/post | Professional post | 300–500 words | Professional framing; ends with engagement question |
| Video outline | Script/talking points | 5–10 min | Optional; screencast or talking-head format |

All five channels should be considered for every BIP task. Mark a channel as "N/A" only when the content genuinely doesn't fit (e.g., a code-heavy topic may skip Instagram).

---

## File Naming Convention

```
docs/bip/{task-id}-{slug}.md
```

Examples:
- `docs/bip/0.45-why-service-catalogs-drift.md`
- `docs/bip/1.3-registry-service-catalog.md`

---

## Frontmatter

Every BIP draft file must start with:

```markdown
---
title: "<Human-readable title>"
task: "<checklist task id, e.g. 0.45>"
channels: [blog, twitter, instagram, linkedin, video]
status: draft  # draft | published
---
```

Set `status: published` and add a `published_at` date when content goes live.

---

## Per-Channel Guidelines

### Blog Post

- **Hook:** Open with a concrete problem or surprising stat — not a definition.
- **Body:** Problem → root cause → naive fix → the real fix → how Cartogra approaches it.
- **CTA:** End with a link to the repo, a follow request, or a question.
- **Length:** 600–1000 words. Lean on subheadings. No walls of text.

### Twitter/X Thread

- **Tweet 1 (hook):** Must stand alone. Bold claim, surprising stat, or provocative question. Max 240 chars.
- **Tweets 2–N:** Each tweet should be a complete thought. Number them (2/ 3/ …) if the thread exceeds 5.
- **Last tweet:** Repo link + follow CTA.
- **Avoid:** Filler tweets ("as I was saying…"). Every tweet earns its place.

### Instagram Carousel

- **Slide 1 (hook):** Large text, bold statement. Must stop the scroll. No body copy.
- **Slides 2–N:** One idea per slide. Mix short text + simple diagram or code snippet.
- **Last slide:** Clear CTA — "Follow @cartogra_dev" or "Link in bio."
- **Caption:** 150–200 words. Summarize the carousel content. Include 3–5 hashtags at the end.
- **Visual note:** Describe the visual layout in the draft so the designer (or Canva template) is unambiguous.

### LinkedIn Article/Post

- **Tone:** More professional than Twitter. Engineers, platform teams, CTOs are the audience.
- **Structure:** 1-sentence hook → 2–3 short paragraphs → question or CTA.
- **Avoid:** Bullet-list-only posts. Mix prose and bullets.
- **End:** Close with a question to drive engagement comments.

### Video Outline

- Mark optional at the top: `> **Optional — do not block the checklist task on this.**`
- **Format:** Segment-by-segment with estimated durations.
- **Hook (0:00–0:30):** Show the pain, not the solution.
- **Problem (0:30–2:00):** Explain what goes wrong and why.
- **Approach (2:00–4:00):** Explain the design decision.
- **Demo (4:00–7:00):** Show it working. Real terminal/browser, no slides.
- **CTA (7:00–end):** Repo link, follow, next episode teaser.

---

## Marking BIP Tasks Done

In `docs/execution-checklist.md`, a BIP task is `[x]` complete when:

1. The `docs/bip/{task-id}-{slug}.md` file exists with all applicable channels drafted.
2. Content is marked `status: draft` at minimum (published = bonus).

A task blocked on "waiting to publish" should still be marked `[x]` once the draft is complete.

---

## References

- [workflow.md — BIP output channels](.../../.claude/rules/workflow.md)
- [execution-checklist.md](../execution-checklist.md)
