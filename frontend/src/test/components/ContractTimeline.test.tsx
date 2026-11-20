import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { ContractTimeline } from '#/components/ContractTimeline'
import type { ContractVersion } from '#/lib/contract-types'

const MANUAL: ContractVersion = {
  id: 'v-1',
  contractId: 'c-1',
  versionNumber: 1,
  label: '1.0.0',
  specHash: 'a'.repeat(64),
  breaking: false,
  createdAt: '2026-11-01T00:00:00Z',
  source: 'manual',
  repositoryFullName: null,
  filePath: null,
}
const DISCOVERED: ContractVersion = {
  ...MANUAL,
  id: 'v-2',
  versionNumber: 2,
  label: '1.1.0',
  source: 'discovery',
  repositoryFullName: 'acme/ledger',
  filePath: 'api/openapi.yaml',
}

describe('ContractTimeline', () => {
  it('shows the repo and path badge only for a discovered version', () => {
    render(<ContractTimeline versions={[DISCOVERED, MANUAL]} selected={2} onSelect={vi.fn()} />)

    const items = within(screen.getByRole('list', { name: 'Version timeline' })).getAllByRole('listitem')
    const badge = within(items[0]).getByText('from acme/ledger/api/openapi.yaml')
    expect(badge).not.toHaveClass('truncate')
    expect(badge).not.toHaveAttribute('title')
    expect(within(items[1]).queryByText(/^from /)).not.toBeInTheDocument()
  })
})
