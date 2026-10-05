import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiFetch } from '#/lib/api'
import { Route } from '#/routes/_authenticated/risks'

import type { PageResult, RegistryService } from '#/lib/registry-types'
import type { Risk, RisksPage } from '#/lib/topology-types'

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
  Link: ({ children, to, search }: { children: React.ReactNode; to: string; search?: Record<string, string> }) => {
    const query = search ? `?${new URLSearchParams(search).toString()}` : ''
    return <a href={`${to}${query}`}>{children}</a>
  },
}))

vi.mock('#/lib/api', () => ({
  apiFetch: vi.fn(),
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

vi.mock('#/components/AppLayout', () => ({
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

function risk(overrides: Partial<Risk> = {}): Risk {
  return {
    id: 'orphan:s1',
    type: 'orphan',
    severity: 'warning',
    title: 'Unowned service: checkout-api',
    explanation: 'checkout-api has no owning team assigned.',
    fix: 'Assign an owning team in the Service Catalog.',
    affectedServices: ['s1'],
    ...overrides,
  }
}

const EMPTY_SERVICES: PageResult<RegistryService> = { items: [], total: 0, limit: 1000, offset: 0 }

function mockRisksCalls(
  risks: Risk[],
  services: PageResult<RegistryService> = EMPTY_SERVICES,
  truncated = false,
) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/services')) return Promise.resolve(services)
    if (path.includes('/v1/topology/risks')) {
      const page: RisksPage = { items: risks, total: risks.length, limit: 200, offset: 0, truncated }
      return Promise.resolve(page)
    }
    return Promise.reject(new Error(`unexpected path: ${path}`))
  })
}

function renderPage() {
  const Page = (Route as any).component
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('RisksPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('shows error alert with traceId on fetch failure', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('SERVER_ERROR', 'risks unavailable', 'trace-risks-1'))
    renderPage()
    expect(await screen.findByText(/risks unavailable/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-risks-1/i)).toBeInTheDocument()
  })

  it('warns that counts understate when a risk source hit its cap', async () => {
    mockRisksCalls([risk()], EMPTY_SERVICES, true)
    renderPage()
    expect(await screen.findByText(/hit their 200-item cap/i)).toBeInTheDocument()
  })

  it('shows no cap warning when nothing was truncated', async () => {
    mockRisksCalls([risk()])
    renderPage()
    await screen.findByText('Unowned service: checkout-api')
    expect(screen.queryByText(/hit their 200-item cap/i)).not.toBeInTheDocument()
  })

  it('shows the empty state when there are no risks', async () => {
    mockRisksCalls([])
    renderPage()
    expect(await screen.findByText('No active risks')).toBeInTheDocument()
  })

  it('renders risks sorted by severity, critical first', async () => {
    mockRisksCalls([
      risk({ id: 'r-warning', severity: 'warning', title: 'Warning risk' }),
      risk({ id: 'r-critical', severity: 'critical', title: 'Critical risk' }),
      risk({ id: 'r-info', severity: 'info', title: 'Info risk' }),
    ])
    renderPage()

    expect(await screen.findByText('Critical risk')).toBeInTheDocument()
    const titles = screen.getAllByText(/risk$/i).map((el) => el.textContent)
    expect(titles).toEqual(['Critical risk', 'Warning risk', 'Info risk'])
  })

  it('filtering by severity narrows the list', async () => {
    mockRisksCalls([
      risk({ id: 'r-warning', severity: 'warning', title: 'Warning risk' }),
      risk({ id: 'r-critical', severity: 'critical', title: 'Critical risk' }),
    ])
    renderPage()
    await screen.findByText('Critical risk')

    fireEvent.click(screen.getByText('Critical').closest('button')!)

    expect(screen.getByText('Critical risk')).toBeInTheDocument()
    expect(screen.queryByText('Warning risk')).not.toBeInTheDocument()
  })

  it('filtering by type narrows the list', async () => {
    mockRisksCalls([
      risk({ id: 'r-orphan', type: 'orphan', title: 'Orphan risk' }),
      risk({ id: 'r-spof', type: 'spof', title: 'SPOF risk' }),
    ])
    renderPage()
    await screen.findByText('Orphan risk')

    fireEvent.click(screen.getByRole('radio', { name: /SPOF/i }))

    expect(screen.getByText('SPOF risk')).toBeInTheDocument()
    expect(screen.queryByText('Orphan risk')).not.toBeInTheDocument()
  })

  it('expanding a card shows explanation and fix', async () => {
    mockRisksCalls([risk({ id: 'r-1', title: 'Unowned service: checkout-api' })])
    renderPage()
    const card = await screen.findByText('Unowned service: checkout-api')
    fireEvent.click(card.closest('button')!)

    expect(screen.getByText(/no owning team assigned/i)).toBeInTheDocument()
    expect(screen.getByText(/assign an owning team/i)).toBeInTheDocument()
  })

  it('dismiss persists across a remount, and can be shown again', async () => {
    mockRisksCalls([risk({ id: 'r-1', title: 'Unowned service: checkout-api' })])
    const { unmount } = renderPage()
    const card = await screen.findByText('Unowned service: checkout-api')
    fireEvent.click(card.closest('button')!)
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))

    unmount()
    mockRisksCalls([risk({ id: 'r-1', title: 'Unowned service: checkout-api' })])
    renderPage()

    expect(await screen.findByText(/show 1 dismissed/i)).toBeInTheDocument()
    expect(screen.queryByText('Unowned service: checkout-api')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText(/show 1 dismissed/i))
    expect(await screen.findByText('Unowned service: checkout-api')).toBeInTheDocument()
  })

  it('summary counts exclude dismissed risks until they are shown again', async () => {
    mockRisksCalls([
      risk({ id: 'r-a', severity: 'critical', title: 'Critical A' }),
      risk({ id: 'r-b', severity: 'critical', title: 'Critical B' }),
    ])
    renderPage()
    const criticalCard = (await screen.findByText('Critical', { selector: 'p, span, div' })).closest('button')!
    expect(criticalCard).toHaveTextContent('2')

    fireEvent.click(screen.getByText('Critical A').closest('button')!)
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))

    expect(screen.getByText('Critical', { selector: 'p, span, div' }).closest('button')).toHaveTextContent('1')
    fireEvent.click(screen.getByText(/show 1 dismissed/i))
    expect(screen.getByText('Critical', { selector: 'p, span, div' }).closest('button')).toHaveTextContent('2')
  })

  it('resolves affected-service chips to names and deep-links to the graph', async () => {
    mockRisksCalls(
      [risk({ id: 'r-1', affectedServices: ['s1'] })],
      { items: [{ id: 's1', name: 'checkout-api' } as RegistryService], total: 1, limit: 1000, offset: 0 },
    )
    renderPage()
    await screen.findByText('Unowned service: checkout-api')

    const chip = screen.getByRole('link', { name: 'checkout-api' })
    expect(chip).toHaveAttribute('href', '/graph?service=s1')
  })
})
