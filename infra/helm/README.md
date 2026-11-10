# Test on a local cluster

`infra/helm/local-test.sh` installs `infra/helm/cartogra` and `infra/helm/acme` on a k3d cluster (`cartogra-local`, http://localhost:8088) with demo values. The gateway creates the Acme tenant and the bootstrap Job seeds it. The script then checks the Acme pods and guest access, and stops at the first failure.

```bash
sh infra/helm/local-test.sh              # delete the cluster if it exists, build images, install, seed, check
sh infra/helm/local-test.sh --no-build   # same, with the images already built
sh infra/helm/local-test.sh --reuse      # keep the cluster and its data, roll out new images
sh infra/helm/local-test.sh --down       # delete the cluster and its data, keep the images
```

Requires `k3d`, `helm`, `kubectl`, `docker` and `curl`. It leaves `~/.kube/config` alone and prints the `KUBECONFIG` export when it finishes. Override the cluster name or port with `CLUSTER` and `PORT`.

Not covered: GHCR push, `deploy.yml`, sealed secrets, TLS, S3 upload, the GitHub mirror scan, alert email delivery, and sizing on the 8GB VM.
