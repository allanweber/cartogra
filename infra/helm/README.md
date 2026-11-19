# Test on a local cluster

`infra/helm/local-test.sh` installs `infra/helm/cartogra` and `infra/helm/acme` on a k3d cluster (`cartogra-local`, http://localhost:8088) with production values. The gateway creates the Acme tenant and the bootstrap Job seeds it. The script then checks the Acme pods, guest access, the ownership flow, a team save, a team member accepting a drift (the registry access check) and alerting, and stops at the first failure. On k3d it turns on `RESEND_TEST_MODE` (the invite token is read from the gateway log) and sets the drift sweep to 15s.

```bash
sh infra/helm/local-test.sh              # delete the cluster if it exists, build images, install, seed, check
sh infra/helm/local-test.sh --no-build   # same, with the images already built
sh infra/helm/local-test.sh --reuse      # keep the cluster and its data, roll out new images
sh infra/helm/local-test.sh --down       # delete the cluster and its data, keep the images
sh infra/helm/local-test.sh --e2e        # also run the Playwright suite against the cluster, one report line per spec file
```

Requires `k3d`, `helm`, `kubectl`, `docker`, `curl` and `jq`. It leaves `~/.kube/config` alone and prints the `KUBECONFIG` export when it finishes. Override the cluster name or port with `CLUSTER` and `PORT`.

Every run writes `build/local-test-report.html`, also when it fails: the result, checks passed, duration, commit, then each step with its time and its passed or failed checks. Override the path with `REPORT`. The page is built by `infra/helm/local-test-report.awk`.

Not covered: GHCR push, `deploy.yml`, sealed secrets, TLS, S3 upload, the GitHub mirror scan, alert email delivery, and sizing on the 8GB VM.
