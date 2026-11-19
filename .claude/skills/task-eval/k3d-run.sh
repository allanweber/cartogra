#!/bin/sh
# Runs local-test.sh, the Playwright suite and the teardown on k3d as one unit under a lock in the shared git dir.
# All worktrees share the cluster cartogra-local, port 8088 and the cartogra-<svc>:k3d image tags,
# so only one worktree at a time can hold this unit. flock waits with no limit and releases on any exit.
#
#   sh .claude/skills/task-eval/k3d-run.sh        run from the worktree root
#
# Last line: K3D local-test=<rc> e2e=<rc>. Exit 0 only if both passed.
set -u

cd "$(git rev-parse --show-toplevel)"
LOCK="$(git rev-parse --path-format=absolute --git-common-dir)/cartogra-k3d.lock"
OWNER="$LOCK.owner"

[ -s "$OWNER" ] && echo "WAIT k3d lock held by: $(cat "$OWNER")"
exec 9>"$LOCK"
flock 9
echo "$(git branch --show-current) $(pwd) $(date -Is)" > "$OWNER"
echo "LOCK acquired"

PF=""
cleanup() {
  [ -n "$PF" ] && kill "$PF" 2>/dev/null
  sh infra/helm/local-test.sh --down
  : > "$OWNER"
}
trap cleanup EXIT INT TERM

# Fresh build and cluster: the :k3d images and the cluster can come from another worktree.
sh infra/helm/local-test.sh
t=$?

e=1
if [ "$t" -eq 0 ]; then
  # The e2e setup reads verification tokens straight from Postgres. Point it at the k3d database,
  # never at the persistent stack on :5436 / :8080.
  kubectl --context k3d-cartogra-local -n cartogra port-forward svc/postgres 5437:5432 >/dev/null 2>&1 &
  PF=$!
  sleep 3
  (cd frontend && \
    E2E_BASE_URL=http://localhost:8088 \
    E2E_API_BASE_URL=http://localhost:8088 \
    E2E_DB_URL=postgresql://cartogra:k3d-test-pw@localhost:5437/cartogra \
    pnpm exec playwright test)
  e=$?
else
  echo "SKIP e2e: local-test.sh failed"
fi

echo "REPORT build/local-test-report.html"
echo "REPORT frontend/playwright-report/index.html"
echo "K3D local-test=$t e2e=$e"
[ "$t" -eq 0 ] && [ "$e" -eq 0 ]
