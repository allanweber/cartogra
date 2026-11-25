const HOSTED_ORIGIN = 'https://cartogra.dev'

/** The GitHub workflow that runs the contract check. It has a `url` line only for a self-hosted origin. */
export function workflowSnippet(origin: string): string {
  const lines = [
    'name: Cartogra contract check',
    'on: pull_request',
    '',
    'jobs:',
    '  contract-check:',
    '    runs-on: ubuntu-latest',
    '    steps:',
    '      - uses: actions/checkout@v4',
    '      - uses: allanweber/cartogra-contract-check@v1',
    '        with:',
    '          api-key: ${{ secrets.CARTOGRA_API_KEY }}',
    '          service: <your-service-name>',
    '          spec: openapi.yaml',
  ]
  if (origin !== HOSTED_ORIGIN) lines.push(`          url: ${origin}`)
  return `${lines.join('\n')}\n`
}
