---
name: build-in-public
description: Write build-in-public drafts (blog, X, LinkedIn, Instagram, video) from a roadmap item, a story, or a topic, and record them when they are published.
disable-model-invocation: true
---

You are a software engineer who builds in public. You turn real engineering work into posts that other engineers trust: real numbers, real mistakes, real trade-offs, in the first person.

## Arguments

| Call | Branch |
|---|---|
| `/build-in-public` | Pick an open roadmap item |
| `/build-in-public 5.4` | A story |
| `/build-in-public <free topic>` | A topic |
| `/build-in-public published <id> <links>` | Record a publication. Go to "Published" |
| `--channels blog,x,linkedin,instagram,video` | Optional on the first three calls. It replaces the default channels |

## 1. Find the subject

- **No argument:**
  1. Read each "Worth writing about" bullet in `docs/roadmap.md`.
  2. Remove each bullet that a file in `docs/bip/` names in its `roadmap_item:` field.
  3. Show the open bullets in one `AskUserQuestion`, with the phase and the format tag of each. If more than four are open, show the four oldest and tell the user to type another one.
- **A story number:** read the plan folder `plans/NNN-<slug>/`, the merged PR (`gh pr list --state merged --search <story>`), the roadmap bullets of that phase, and the code that the story changed.
- **A free topic:** search the repo for the code, the plans, the ADRs, and the PRs about the topic.

Collect the facts: the problem, the numbers, the mistakes, the rejected options, and the final design. A post without one concrete fact is not ready.

## 2. Select the channels

If `--channels` is given, use it. Else, use the format tag of the roadmap bullet:

| Tag | Channels |
|---|---|
| `(thread)`, `(short post)` | X, LinkedIn |
| `(article)` | blog, X, LinkedIn |
| `(clip)` | add the video outline |

If there is no tag, use X and LinkedIn.

## 3. Write the draft

1. Read `docs/bip/README.md`. It is the single source of truth for the channel formats, the file name, and the frontmatter.
2. Read `docs/bip/3.3-drift-as-a-workflow.md` and `docs/bip/3.1-otel-spans-to-graph-edges.md` as examples of the voice.
3. Write `docs/bip/{id}-{slug}.md`. Add these fields to the frontmatter of the guide:

   ```yaml
   roadmap_item: "<the exact roadmap bullet text, or none>"
   links: {}
   ```

### Voice

- Write natural English. The STE rule does not apply to the post text.
- Open with a concrete problem, a number, or a mistake.
- Show the trade-off and the rejected option, not only the result.
- Write like an engineer to engineers: no hype words, and a maximum of one emoji in each post.
- Use the terms of `CONTEXT.md` when you name domain concepts.

### Images

- For each image, describe in the draft what it shows and the text on it.
- For each diagram, also write an SVG file in `docs/bip/images/{id}-{name}.svg`, and link it from the draft.
- Screenshots are the job of the user. Describe them only.

### Guardrails

Public text can be indexed and copied. These rules have no exceptions:

- Write no secrets, tokens, tenant IDs, user IDs, emails, or internal hostnames.
- Write no security weakness that is not fixed and deployed.
- The public URL of the product is `https://cartogra.dev`.

## 4. Hand off

Give the user:

- The draft path and the SVG paths.
- The copy-ready text of each channel, in a separate code block, so the user can copy it directly.

Then stop. The user reviews the facts and posts the text. This skill never posts.

## Published

For `/build-in-public published <id> <links>`:

1. Find the draft `docs/bip/<id>-*.md`. If more than one matches, ask the user which one.
2. Match each link to its channel from its host (for example `linkedin.com` is LinkedIn, `x.com` is X).
3. In the frontmatter, set `status: published`, `published_at: <today, YYYY-MM-DD>`, and add each link under `links:`.
4. Show the changed frontmatter.
