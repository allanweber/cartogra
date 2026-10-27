import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { DriftTab } from '#/components/DriftTab'
import { apiFetch, apiMutate } from '#/lib/api'

import type { Drift } from '#/lib/topology-types'

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

const NAMES = new Map([
  ['a', { name: 'reporting-service' }],
  ['b', { name: 'payments-service' }],
  ['c', { name: 'fx-service' }],
])

function drift(overrides: Partial<Drift>): Drift {
  return {
    id: 'd1',
    type: 'UNDECLARED',
    sourceServiceId: 'a',
    targetServiceId: 'b',
    detectedAt: '2026-10-20T12:00:00Z',
    resolvedAt: null,
    resolution: null,
    ...overrides,
  }
}

function renderTab(onSelectDrift = vi.fn(), selectedDriftId: string | null = null, serviceId?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  render(
    <QueryClientProvider client={client}>
      <DriftTab
        nodesById={NAMES}
        serviceId={serviceId}
        selectedDriftId={selectedDriftId}
        onSelectDrift={onSelectDrift}
      />
    </QueryClientProvider>,
  )
  return { onSelectDrift, invalidate }
}

function mockDrifts(items: Drift[]) {
  vi.mocked(apiFetch).mockResolvedValue({ items, total: items.length, limit: 200, offset: 0 })
}

describe('DriftTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows an empty state when nothing drifts', async () => {
    mockDrifts([])
    renderTab()
    expect(await screen.findByText(/no drift/i)).toBeInTheDocument()
  })

  it('scopes the list to drifts touching the given service', async () => {
    mockDrifts([drift({}), drift({ id: 'd2', type: 'MISSING', sourceServiceId: 'c', targetServiceId: 'a' })])
    renderTab(vi.fn(), null, 'c')
    expect(await screen.findByText('fx-service → reporting-service')).toBeInTheDocument()
    expect(screen.queryByText('reporting-service → payments-service')).not.toBeInTheDocument()
  })

  it('shows a per-service empty state when the service has no drift', async () => {
    mockDrifts([drift({})])
    renderTab(vi.fn(), null, 'c')
    expect(await screen.findByText(/no drift on fx-service/i)).toBeInTheDocument()
  })

  it('confirms Accept with a non-destructive button', async () => {
    mockDrifts([drift({})])
    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: /^Accept/ }))
    const confirm = screen.getAllByRole('button', { name: /^Accept/ }).at(-1)!
    expect(confirm).toHaveAttribute('data-variant', 'default')
  })

  it('lists drifts with service names and type', async () => {
    mockDrifts([drift({}), drift({ id: 'd2', type: 'MISSING', sourceServiceId: 'a', targetServiceId: 'c' })])
    renderTab()
    expect(await screen.findByText('reporting-service → payments-service')).toBeInTheDocument()
    expect(screen.getByText('reporting-service → fx-service')).toBeInTheDocument()
    expect(screen.getByText('Undeclared')).toBeInTheDocument()
    expect(screen.getByText('Missing')).toBeInTheDocument()
  })

  it('offers Accept for undeclared and Remove declared for missing drift', async () => {
    mockDrifts([drift({}), drift({ id: 'd2', type: 'MISSING', targetServiceId: 'c' })])
    renderTab()
    await screen.findByText('Undeclared')
    expect(screen.getByRole('button', { name: /^Accept/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Remove declared/ })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^Dismiss/ })).toHaveLength(2)
  })

  it('selecting a row reports the drift so the canvas can highlight its edge', async () => {
    mockDrifts([drift({})])
    const { onSelectDrift } = renderTab()
    fireEvent.click(await screen.findByText('reporting-service → payments-service'))
    expect(onSelectDrift).toHaveBeenCalledWith(expect.objectContaining({ id: 'd1' }))
  })

  it.each([
    ['UNDECLARED', 'Accept', 'ACCEPT_OBSERVED'],
    ['MISSING', 'Remove declared', 'REMOVE_DECLARED'],
  ] as const)('confirming %s resolution posts %s and refreshes dependent queries', async (type, label, action) => {
    mockDrifts([drift({ type })])
    vi.mocked(apiMutate).mockResolvedValue(drift({ type, resolution: 'ACCEPTED' }))
    const { invalidate } = renderTab()

    fireEvent.click(await screen.findByRole('button', { name: new RegExp(`^${label}`) }))
    fireEvent.click(screen.getAllByRole('button', { name: new RegExp(`^${label}`) }).at(-1)!)

    await waitFor(() => expect(apiMutate).toHaveBeenCalledWith('/v1/topology/drifts/d1/resolve', { action }))
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['graph'] }))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['drifts'] })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['risks'] })
  })

  it('dismiss posts DISMISS after confirmation', async () => {
    mockDrifts([drift({})])
    vi.mocked(apiMutate).mockResolvedValue(drift({ resolution: 'DISMISSED' }))
    renderTab()

    fireEvent.click(await screen.findByRole('button', { name: /^Dismiss/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /^Dismiss/ }).at(-1)!)

    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/v1/topology/drifts/d1/resolve', { action: 'DISMISS' }),
    )
  })

  it('shows the error with its trace id when resolving fails', async () => {
    const { ApiError } = await import('#/lib/api')
    mockDrifts([drift({})])
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('FORBIDDEN', 'Not allowed', 'trace-drift-1'))
    renderTab()

    fireEvent.click(await screen.findByRole('button', { name: /^Accept/ }))
    fireEvent.click(screen.getAllByRole('button', { name: /^Accept/ }).at(-1)!)

    expect(await screen.findByText(/trace-drift-1/)).toBeInTheDocument()
  })

  it('shows the trace id when the list fails to load', async () => {
    const { ApiError } = await import('#/lib/api')
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('SERVER_ERROR', 'drift unavailable', 'trace-list-1'))
    renderTab()
    expect(await screen.findByText(/trace-list-1/)).toBeInTheDocument()
  })
})
