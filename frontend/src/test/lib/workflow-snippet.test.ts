import { describe, expect, it } from 'vitest'
import { workflowSnippet } from '#/lib/workflow-snippet'

describe('workflowSnippet', () => {
  it('has the action, the secret, the service, and the spec, and no url line on cartogra.dev', () => {
    const snippet = workflowSnippet('https://cartogra.dev')
    expect(snippet).toContain('uses: allanweber/cartogra-contract-check@v1')
    expect(snippet).toContain('api-key: ${{ secrets.CARTOGRA_API_KEY }}')
    expect(snippet).toContain('service: <your-service-name>')
    expect(snippet).toContain('spec: openapi.yaml')
    expect(snippet).not.toContain('url:')
  })

  it('has a url line on a self-hosted origin', () => {
    expect(workflowSnippet('http://localhost:5173')).toContain('url: http://localhost:5173')
  })
})
