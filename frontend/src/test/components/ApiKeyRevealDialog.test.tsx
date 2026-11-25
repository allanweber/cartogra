import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiKeyRevealDialog } from '#/components/ApiKeyRevealDialog'
import type { CreatedApiKey } from '#/lib/registry-types'

function key(scopes: string[]): CreatedApiKey {
  return {
    id: 'k1',
    name: 'ci',
    prefix: 'cgk_abcdef',
    scopes,
    status: 'active',
    expiresAt: null,
    lastUsedAt: null,
    createdAt: '2026-11-25T00:00:00Z',
    key: 'cgk_secret-value-123',
  }
}

describe('ApiKeyRevealDialog', () => {
  it('shows the workflow snippet for a ci:check key', () => {
    render(<ApiKeyRevealDialog created={key(['ci:check'])} onClose={() => {}} />)
    const snippet = screen.getByTestId('api-key-workflow')
    expect(snippet).toHaveTextContent('uses: allanweber/cartogra-contract-check@v1')
    expect(snippet).toHaveTextContent('secrets.CARTOGRA_API_KEY')
    expect(snippet).not.toHaveTextContent('cgk_secret-value-123')
    expect(screen.getByRole('button', { name: 'Copy workflow' })).toBeInTheDocument()
  })

  it('shows no snippet without ci:check', () => {
    render(<ApiKeyRevealDialog created={key(['registry:read'])} onClose={() => {}} />)
    expect(screen.queryByTestId('api-key-workflow')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy workflow' })).not.toBeInTheDocument()
  })
})
