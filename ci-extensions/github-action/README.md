# Cartogra Contract Check

This GitHub Action sends the OpenAPI spec of your repository to [Cartogra](https://cartogra.dev). Cartogra compares the spec with the last stored version of the service. If the change is breaking, the step fails. The job summary shows a table of the changes and the consumers that the change affects.

> This repository is a copy. The source is in [`allanweber/cartogra`](https://github.com/allanweber/cartogra/tree/main/ci-extensions/github-action). Open issues and pull requests on `allanweber/cartogra`.

## Usage

1. In Cartogra, open **Settings > API keys** and create a key with the scope `ci:check`.
2. In GitHub, add the key as the secret `CARTOGRA_API_KEY` of the repository or of the organization.
3. Add this workflow as `.github/workflows/cartogra-check.yml`:

```yaml
name: Cartogra contract check
on: pull_request
jobs:
  contract-check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: allanweber/cartogra-contract-check@v1
        with:
          api-key: ${{ secrets.CARTOGRA_API_KEY }}
          service: <your-service-name>
          spec: openapi.yaml
```

The Action needs no `GITHUB_TOKEN` permissions.

## Two references, one source

| Reference | When to use it |
|---|---|
| `allanweber/cartogra-contract-check@v1` | The Marketplace copy. Use this reference in most workflows. `v1` moves to each new `1.x.y` release. |
| `allanweber/cartogra/ci-extensions/github-action@<ref>` | The source in the Cartogra repository. Use it to test a branch or a commit. |

## Inputs

| Input | Required | Default | Description |
|---|---|---|---|
| `api-key` | yes | | A Cartogra API key with the scope `ci:check`. Keep it in a secret. |
| `service` | yes | | The id or the name of the service in Cartogra. |
| `spec` | yes | | The path of the OpenAPI file, relative to the workspace. The maximum size is 1,048,576 characters. |
| `url` | no | `https://cartogra.dev` | The base URL of your Cartogra. It must use `https`. Plain `http` is accepted only for `localhost` and `127.0.0.1`. |
| `fail-on-breaking` | no | `true` | Set `false` to let a breaking change pass with a warning, for example for a planned break that the consumers agreed to. |

## Outputs

| Output | Description |
|---|---|
| `is-breaking` | `true` when the change is breaking, else `false`. |
| `changes-count` | The number of changes that the check found. |

The Action sets the outputs only when the check gives a result, or when Cartogra has no baseline. The Action sets no outputs after an error.

## Results and errors

| Result | Step | Note |
|---|---|---|
| 200, compatible | passes | The summary shows the changes, or "No changes". |
| 200, breaking | fails | An error annotation names the service and the number of breaking changes. With `fail-on-breaking: false`, the step passes with a warning. |
| 404 `BASELINE_NOT_FOUND` | passes | A warning annotation. A new service has no stored version yet, so nothing can break. |
| 400 `VALIDATION_ERROR` | fails | The request is not valid, for example an empty spec. |
| 401 | fails | The key is not valid, or it was revoked. |
| 403 | fails | The key does not have the scope `ci:check`. |
| 404 `SERVICE_NOT_FOUND` | fails | Cartogra has no service with this id or name. |
| 409 `SERVICE_AMBIGUOUS` | fails | More than one service has this name. Use the id of the service. |
| 422 | fails | The spec is not a valid OpenAPI document. |
| 429 or 5xx | fails after 3 tries | The Action retries. On 429 it waits `Retry-After` seconds (maximum 30 s). Else it waits 2 s, then 4 s. |
| Network error or timeout | fails after 3 tries | Each try has a timeout of 30 s. |

Each error message has the HTTP status, the error code, and the message from Cartogra.

## Security

- The Action masks the key in the log before it writes other output.
- The Action sends the key only in the header `X-Cartogra-Api-Key`.
- The Action refuses a `url` with plain `http`, except to `localhost` and `127.0.0.1`.
- The Action does not follow a redirect, so the key cannot go to a second host. Set `url` to the final address.

## Development

The Action has no dependencies. It uses Node 24 with the built-in `fetch`.

```sh
cd ci-extensions/github-action
node --test
```

To release a version, set `version` in `package.json`, merge, and push the tag `action-vX.Y.Z` on `allanweber/cartogra`. The release workflow copies this folder to `allanweber/cartogra-contract-check`, tags `vX.Y.Z`, moves `vX`, and creates the GitHub Release. Do not edit the copy by hand. The next release replaces it.

## License

Apache-2.0. See [LICENSE](LICENSE).
