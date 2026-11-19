#!/bin/sh
# Runs local-test.sh --e2e (checks + Playwright, one report) and the teardown on k3d as one unit under a lock
# in the shared git dir. All worktrees share the cluster cartogra-local, port 8088 and the cartogra-<svc>:k3d
# image tags, so only one worktree at a time can hold this unit. flock waits with no limit and releases on any exit.
#
#   sh .claude/skills/task-eval/k3d-run.sh        run from the worktree root
#
# Last line: K3D local-test=<rc>. Exit 0 only if every check and every e2e spec passed.
set -u

cd "$(git rev-parse --show-toplevel)"
LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/cartogra-k3d.lock"
OWNER="$LOCK.owner"

exec 9>"$LOCK"
if ! flock -n 9; then
  echo "WAIT k3d lock held by: $(cat "$OWNER" 2>/dev/null || echo unknown)"
  flock 9
fi
echo "$(git branch --show-current) $(pwd) $(date -Is)" > "$OWNER"
echo "LOCK acquired"
# local-test.sh takes the same lock itself; this run already holds it for both calls below.
export CARTOGRA_K3D_LOCKED=1

cleanup() {
  sh infra/helm/local-test.sh --down
  : > "$OWNER"
}
trap cleanup EXIT INT TERM

# Fresh build and cluster: the :k3d images and the cluster can come from another worktree.
sh infra/helm/local-test.sh --e2e
t=$?

echo "REPORT build/local-test-report.html"
echo "REPORT frontend/playwright-report/index.html"
echo "K3D local-test=$t"
[ "$t" -eq 0 ]
