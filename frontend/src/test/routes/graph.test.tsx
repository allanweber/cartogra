import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { useSyncExternalStore } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiFetch } from '#/lib/api'
import { Route } from '#/routes/_authenticated/graph'

import type { PageResult, RegistryTeam } from '#/lib/registry-types'
import type { Graph } from '#/lib/topology-types'

// Minimal reactive stand-in for TanStack Router's search-param state: real enough that
// navigate({ search }) calls re-render the component with the updated filters/selection.
let mockSearch: Record<string, unknown> = {}
const searchListeners = new Set<() => void>()
function subscribeMockSearch(listener: () => void) {
  searchListeners.add(listener)
  return () => searchListeners.delete(listener)
}
const navigateMock = vi.fn((opts: { search: unknown }) => {
  mockSearch = typeof opts.search === 'function' ? opts.search(mockSearch) : ((opts.search ?? {}) as Record<string, unknown>)
  searchListeners.forEach((l) => l())
})

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => ({
    ...opts,
    useSearch: () => useSyncExternalStore(subscribeMockSearch, () => mockSearch),
    useNavigate: () => navigateMock,
  }),
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>
      {children}
    </a>
  ),
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

const EMPTY_TEAMS: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }

function makeGraph(overrides: Partial<Graph> = {}): Graph {
  return {
    nodes: [
      { serviceId: 's1', name: 'api-gateway', teamId: null, tier: 'CRITICAL', healthStatus: 'HEALTHY' },
      { serviceId: 's2', name: 'auth-service', teamId: null, tier: 'STANDARD', healthStatus: 'DEGRADED' },
    ],
    edges: [{ source: 's1', target: 's2', dependencyType: 'DECLARED', protocol: 'HTTP', metadata: 'internal-only' }],
    truncated: false,
    ...overrides,
  }
}

function mockGraphCalls(byQuery: (query: string) => Graph) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
    const query = path.split('?')[1] ?? ''
    return Promise.resolve(byQuery(query))
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

describe('GraphPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSearch = {}
  })

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('shows error alert with traceId on fetch failure', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('SERVER_ERROR', 'graph unavailable', 'trace-graph-1'))
    renderPage()
    expect(await screen.findByText(/graph unavailable/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-graph-1/i)).toBeInTheDocument()
  })

  it('renders the graph canvas once data loads, requesting declared by default', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    expect(await screen.findByRole('group', { name: /service dependency graph/i })).toBeInTheDocument()
    expect(screen.getByText('Select a node to see its details.')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('type=DECLARED'))
  })

  it('arriving with a ?service= deep link preselects that node in the side panel', async () => {
    mockGraphCalls(() => makeGraph())
    mockSearch = { service: 's2' }
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(await screen.findAllByText('auth-service')).not.toHaveLength(0)
    expect(screen.getByText('degraded')).toBeInTheDocument()
  })

  it('selecting a node shows its details and neighbor links in the side panel', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    const nodeCircle = svg.querySelector('.graph-node circle')
    expect(nodeCircle).toBeTruthy()
    const nodeGroup = nodeCircle!.parentElement as unknown as SVGGElement
    nodeGroup.dispatchEvent(new MouseEvent('click', { bubbles: true }))

    expect(await screen.findAllByText('api-gateway')).not.toHaveLength(0)
    expect(screen.getByRole('link', { name: 'auth-service' })).toBeInTheDocument()
    expect(screen.getByText('HTTP')).toBeInTheDocument()
    expect(screen.getByText('internal-only')).toBeInTheDocument()
    expect(screen.getByText('View in catalog →')).toBeInTheDocument()
  })

  it('exposes edge protocol and metadata as a native hover tooltip on the canvas', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    const title = svg.querySelector('.graph-link title')
    expect(title?.textContent).toBe('HTTP — internal-only')
  })

  it('toggling to Observed triggers a server refetch with type=OBSERVED, not client-side filtering', async () => {
    mockGraphCalls((query) =>
      query.includes('OBSERVED') ? makeGraph({ nodes: [], edges: [] }) : makeGraph(),
    )
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    fireEvent.click(screen.getByRole('radio', { name: 'Observed' }))

    expect(await screen.findByText('No services to graph yet.')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('type=OBSERVED'))
  })

  it('picking a team triggers a server refetch scoped by teamId', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams')) {
        const page: PageResult<RegistryTeam> = {
          items: [{ id: 'team-1', tenantId: 't1', name: 'Platform', createdAt: '', updatedAt: '' }],
          total: 1,
          limit: 200,
          offset: 0,
        }
        return Promise.resolve(page)
      }
      return Promise.resolve(makeGraph())
    })
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })
    vi.mocked(apiFetch).mockClear()

    fireEvent.click(screen.getByRole('combobox'))
    fireEvent.click(await screen.findByRole('option', { name: 'Platform' }))

    expect(await screen.findByRole('group', { name: /service dependency graph/i })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('teamId=team-1'))
  })

  it('shows a dismissible banner when the graph is truncated', async () => {
    mockGraphCalls(() => makeGraph({ truncated: true }))
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(screen.getByText(/has been truncated/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByText(/has been truncated/i)).not.toBeInTheDocument()
  })

  it('shows a dedicated empty state for a tenant with no services', async () => {
    mockGraphCalls(() => makeGraph({ nodes: [], edges: [] }))
    renderPage()
    expect(await screen.findByText('No services to graph yet.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /go to catalog/i })).toBeInTheDocument()
  })
})
