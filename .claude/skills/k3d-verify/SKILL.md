---
name: k3d-verify
description: Placeholder. Will run infra/helm/local-test.sh on the k3d cluster for a plan. Not implemented.
disable-model-invocation: true
---

This skill is a placeholder. It does nothing yet.

Tell the user that `k3d-verify` is not implemented, then stop.

## Future steps

1. Run `sh infra/helm/local-test.sh --e2e` for the plan.
2. Stop the k3d cluster after the run. Leave no containers.
3. Open the HTML report.
