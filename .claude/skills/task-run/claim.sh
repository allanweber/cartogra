#!/bin/sh
# Picks the next available story of the current roadmap phase (or the story given as $1) and claims its issue.
# Pick and claim run under one lock in the shared git dir, so two worktrees cannot claim the same story.
#
#   sh .claude/skills/task-run/claim.sh          next available story
#   sh .claude/skills/task-run/claim.sh 5.4      that story, if it is available
#   DRY_RUN=1 sh .claude/skills/task-run/claim.sh  pick only, no claim
#
# stdout, last line on success:  CLAIMED <N.M> <issue> <slug>
# Also prints one SKIP line for each skipped story, and STALE for a [ ] story that already shipped.
# Exit 0 = claimed, 3 = no story available, other = error.
set -eu

cd "$(git rev-parse --show-toplevel)"
LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/cartogra-claim.lock"
WANT="${1:-}"
git fetch -q origin main

exec 9>"$LOCK"
flock 9

ROADMAP="$(git show origin/main:docs/roadmap.md)"
start=$(printf '%s\n' "$ROADMAP" | grep -n '^## ⬜' | head -1 | cut -d: -f1)
[ -n "$start" ] || { echo "no open phase in docs/roadmap.md" >&2; exit 3; }
phase=$(printf '%s\n' "$ROADMAP" | tail -n +"$start" | awk 'NR>1 && /^## /{exit} {print}')

done_story() {
  printf '%s\n' "$phase" | grep -q "^- \[x\] \*\*$1 " && return 0
  [ "$(gh issue list --state closed --search "[$1] in:title" --json title --jq "[.[]|select(.title|startswith(\"[$1]\"))]|length")" -gt 0 ]
}

ids=$(printf '%s\n' "$phase" | grep -oP '^- \[ \] \*\*\K[0-9]+\.[0-9]+')
[ -n "$WANT" ] && ids="$WANT"

for id in $ids; do
  line=$(printf '%s\n' "$phase" | grep -m1 "^- \[.\] \*\*$id ") || { echo "SKIP $id not-in-current-phase"; continue; }
  issue=$(gh issue list --state all --search "[$id] in:title" \
    --json number,title,state,assignees,labels \
    --jq "[.[]|select(.title|startswith(\"[$id]\"))][0] // empty | \"\(.number) \(.state) \(.assignees|length) \(.labels|map(.name)|index(\"working\")!=null)\"")
  [ -n "$issue" ] || { echo "SKIP $id no-issue"; continue; }
  set -- $issue; num=$1; state=$2; assignees=$3; working=$4
  shipped=$(git log origin/main --oneline | grep -c "($id)\$" || true)

  if [ "$state" = CLOSED ] || [ "$shipped" -gt 0 ]; then
    printf '%s\n' "$line" | grep -q '^- \[ \]' && echo "STALE $id #$num issue=$state commits=$shipped"
    echo "SKIP $id shipped"; continue
  fi
  [ "$working" = true ] || [ "$assignees" -gt 0 ] && { echo "SKIP $id taken"; continue; }

  blocked=""
  for dep in $(printf '%s\n' "$line" | grep -oP 'Depends on \K[0-9]+\.[0-9]+' || true); do
    done_story "$dep" || blocked="$blocked $dep"
  done
  [ -z "$blocked" ] || { echo "SKIP $id depends-on$blocked"; continue; }

  title=$(printf '%s\n' "$line" | sed -E 's/^- \[ \] \*\*[0-9.]+ — ([^*]+)\*\*.*/\1/')
  slug=$(printf '%s' "$title" | tr 'A-Z' 'a-z' | sed -E 's/[^a-z0-9]+/-/g; s/^-|-$//g' | cut -d- -f1-4)
  [ "${DRY_RUN:-}" = 1 ] || gh issue edit "$num" --add-assignee @me --add-label working >/dev/null
  echo "CLAIMED $id $num $slug"
  exit 0
done

exit 3
