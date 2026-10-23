import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { UnresolvedSpanServices } from '#/components/UnresolvedSpanServices'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'

import type { UnresolvedSpanService } from '#/lib/topology-types'

vi.mock('#/lib/api', () => ({
  apiFetch: vi.fn(),
  apiMutate: vi.fn(),
  ApiError: class extends Error {
    code: string
    traceId: string
    constructor(code: string, message: string, traceId: string) {
      super(message)
      this.code = code
      this.traceId = traceId
    }
  },
}))

vi.mock('#/components/RegisterServiceDrawer', () => ({
  RegisterServiceDrawer: ({
    open,
    defaultName,
    onRegistered,
  }: {
    open: boolean
    defaultName?: string
    onRegistered?: (service: { id: string }) => void
  }) =>
    open ? (
      <div role="dialog" aria-label="Register service">
        <span>prefill:{defaultName}</span>
        <button type="button" onClick={() => onRegistered?.({ id: 'svc-new' })}>
          Fake register
        </button>
      </div>
    ) : null,
}))

function item(overrides: Partial<UnresolvedSpanService>): UnresolvedSpanService {
  return {
    id: 'u1',
    name: 'stripe-gateway',
    firstSeenAt: new Date(Date.now() - 86400_000).toISOString(),
    lastSeenAt: new Date(Date.now() - 120_000).toISOString(),
    seenCount: 412,
    resolvedAt: null,
    resolution: null,
    ...overrides,
  }
}

function mockList(items: UnresolvedSpanService[], total = items.length) {
  vi.mocked(apiFetch).mockResolvedValue({ items, total, limit: 20, offset: 0 })
}

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <UnresolvedSpanServices />
    </QueryClientProvider>,
  )
}

describe('UnresolvedSpanServices', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists names with seen count and last seen', async () => {
    mockList([item({}), item({ id: 'u2', name: 'legacy-ldap', seenCount: 37 })])
    renderList()

    const list = await screen.findByRole('list', { name: 'Unresolved services' })
    expect(within(list).getByText('stripe-gateway')).toBeInTheDocument()
    expect(within(list).getByText('412× · 2m ago')).toBeInTheDocument()
    expect(within(list).getByText('legacy-ldap')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Unresolved services (2)' })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/v1/topology/unresolved-span-services?limit=20&offset=0')
  })

  it('shows an empty state', async () => {
    mockList([])
    renderList()

    expect(await screen.findByText('Every traced call resolves to a catalog service.')).toBeInTheDocument()
  })

  it('shows the trace id on error', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('INTERNAL', 'Boom', 'trace-unres-1'))
    renderList()

    expect(await screen.findByText(/Boom/)).toBeInTheDocument()
    expect(screen.getByText(/trace-unres-1/)).toBeInTheDocument()
  })

  it('dismisses after confirmation and drops the row', async () => {
    mockList([item({})])
    vi.mocked(apiMutate).mockResolvedValue(item({ resolution: 'DISMISSED' }))
    renderList()

    fireEvent.click(await screen.findByRole('button', { name: 'Dismiss' }))
    const dialog = await screen.findByRole('dialog')
    mockList([])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Dismiss' }))

    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/v1/topology/unresolved-span-services/u1/dismiss', {}),
    )
    await waitFor(() => expect(screen.queryByText('stripe-gateway')).not.toBeInTheDocument())
  })

  it('opens the register drawer pre-filled and hides the row once registered', async () => {
    mockList([item({})])
    renderList()

    fireEvent.click(await screen.findByRole('button', { name: 'Create service' }))
    expect(screen.getByText('prefill:stripe-gateway')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Fake register' }))

    expect(await screen.findByRole('heading', { name: 'Unresolved services (0)' })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Unresolved services' })).not.toBeInTheDocument()
  })

  it('shows more by raising the limit', async () => {
    mockList([item({})], 25)
    renderList()

    fireEvent.click(await screen.findByRole('button', { name: 'Show more (1 of 25)' }))

    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith('/v1/topology/unresolved-span-services?limit=40&offset=0'),
    )
  })
})
