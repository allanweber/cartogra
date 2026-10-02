import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { useSyncExternalStore } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, apiFetch } from '#/lib/api'
import { Route } from '#/routes/_authenticated/graph'

import type { PageResult, RegistryTeam } from '#/lib/registry-types'
import type { BlastRadius, Cycles, Graph, Spofs } from '#/lib/topology-types'

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

const EMPTY_BLAST_RADIUS: BlastRadius = {
  serviceId: 's1',
  upstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
  downstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
  maxDepth: 3,
}

const EMPTY_CYCLES: Cycles = { cycles: [], truncated: false }
const EMPTY_SPOFS: Spofs = { threshold: 5, rationale: 'rationale', items: [] }

function mockGraphCalls(
  byQuery: (query: string) => Graph,
  blastRadius: BlastRadius = EMPTY_BLAST_RADIUS,
  cycles: Cycles = EMPTY_CYCLES,
  spofs: Spofs = EMPTY_SPOFS,
  services: { id: string; name: string }[] = [],
) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/services')) return Promise.resolve({ items: services, total: services.length, limit: 1000, offset: 0 })
    if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
    if (path.includes('/v1/topology/blast-radius/')) return Promise.resolve(blastRadius)
    if (path.includes('/v1/topology/cycles')) return Promise.resolve(cycles)
    if (path.includes('/v1/topology/spofs')) return Promise.resolve(spofs)
    const query = path.split('?')[1] ?? ''
    return Promise.resolve(byQuery(query))
  })
}

function renderPage() {
  const Page = (Route as any).component
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const result = render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
  return { ...result, client }
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
    expect(screen.getByText('Select a service to see what breaks if it goes down.')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('type=DECLARED'))
  })

  it('arriving with a ?service= deep link preselects that node in the side panel', async () => {
    mockGraphCalls(() => makeGraph())
    mockSearch = { service: 's2' }
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(await screen.findAllByText('auth-service')).not.toHaveLength(0)
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Details' }))
    expect(screen.getByText('degraded')).toBeInTheDocument()
  })

  it('selecting a node shows the Inspector panel, defaulting to its blast radius', async () => {
    const blastRadius: BlastRadius = {
      serviceId: 's1',
      upstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
      downstream: {
        entries: [{ serviceId: 's2', name: 'auth-service', teamId: null, tier: null, healthStatus: 'HEALTHY', distance: 1 }],
        depthTruncated: false,
        nodesBeyondDepth: 0,
        nodeCapTruncated: false,
        nodesBeyondCap: 0,
      },
      maxDepth: 3,
    }
    mockGraphCalls(() => makeGraph(), blastRadius)
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    const nodeCircle = svg.querySelector('.graph-node circle')
    expect(nodeCircle).toBeTruthy()
    const nodeGroup = nodeCircle!.parentElement as unknown as SVGGElement
    nodeGroup.dispatchEvent(new MouseEvent('click', { bubbles: true }))

    expect(await screen.findAllByText('api-gateway')).not.toHaveLength(0)
    expect(screen.getByRole('tab', { name: 'Blast Radius' })).toHaveAttribute('data-state', 'active')
    expect(await screen.findAllByText('auth-service')).not.toHaveLength(0)
    expect(screen.getByText('1 hop away')).toBeInTheDocument()
    expect(screen.getByText('View in catalog →')).toBeInTheDocument()
  })

  it('warns and stops dimming when the blast radius is truncated', async () => {
    const blastRadius: BlastRadius = {
      serviceId: 's1',
      upstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
      downstream: {
        entries: [{ serviceId: 's2', name: 'auth-service', teamId: null, tier: null, healthStatus: 'HEALTHY', distance: 1 }],
        depthTruncated: true,
        nodesBeyondDepth: 4,
        nodeCapTruncated: false,
        nodesBeyondCap: 0,
      },
      maxDepth: 3,
    }
    mockGraphCalls(() => makeGraph(), blastRadius)
    mockSearch = { service: 's1' }
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    expect(await screen.findByText(/partial blast radius/i)).toBeInTheDocument()
    const opacities = [...svg.querySelectorAll<SVGGElement>('.graph-node')].map((g) => g.style.opacity)
    expect(opacities.every((o) => o === '' || o === '1')).toBe(true)
  })

  it('a slow blast-radius response for a previous selection never overwrites the current one', async () => {
    const entry = (serviceId: string, name: string) => ({
      serviceId, name, teamId: null, tier: null, healthStatus: 'HEALTHY' as const, distance: 1,
    })
    const result = (serviceId: string, name: string): BlastRadius => ({
      ...EMPTY_BLAST_RADIUS,
      serviceId,
      downstream: { ...EMPTY_BLAST_RADIUS.downstream, entries: [entry(`x-${serviceId}`, name)] },
    })
    let resolveFirst: (value: BlastRadius) => void = () => {}
    const firstPending = new Promise<BlastRadius>((resolve) => {
      resolveFirst = resolve
    })
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/services')) return Promise.resolve({ items: [], total: 0, limit: 1000, offset: 0 })
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.includes('/v1/topology/blast-radius/s1')) return firstPending
      if (path.includes('/v1/topology/blast-radius/s2')) return Promise.resolve(result('s2', 'impacted-by-auth'))
      if (path.includes('/v1/topology/cycles')) return Promise.resolve(EMPTY_CYCLES)
      if (path.includes('/v1/topology/spofs')) return Promise.resolve(EMPTY_SPOFS)
      return Promise.resolve(makeGraph())
    })
    mockSearch = { service: 's1' }
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    act(() => {
      navigateMock({ search: { service: 's2' } })
    })
    expect(await screen.findByText('impacted-by-auth')).toBeInTheDocument()

    await act(async () => {
      resolveFirst(result('s1', 'stale-from-api-gateway'))
      await firstPending
    })

    expect(screen.getByText('impacted-by-auth')).toBeInTheDocument()
    expect(screen.queryByText('stale-from-api-gateway')).not.toBeInTheDocument()
  })

  it('Escape that was already handled by an overlay does not clear the selection', async () => {
    mockGraphCalls(() => makeGraph())
    mockSearch = { service: 's1' }
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })
    expect(await screen.findAllByText('api-gateway')).not.toHaveLength(0)

    const handled = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })
    handled.preventDefault()
    window.dispatchEvent(handled)
    expect(screen.getAllByText('api-gateway')).not.toHaveLength(0)
    expect(screen.queryByText(/select a service/i)).not.toBeInTheDocument()
  })

  it('selecting a node via keyboard (Enter) shows the same panel as a click', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    const nodeCircle = svg.querySelector('.graph-node circle')
    const nodeGroup = nodeCircle!.parentElement as unknown as SVGGElement
    nodeGroup.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))

    expect(await screen.findAllByText('api-gateway')).not.toHaveLength(0)
    expect(screen.getByRole('tab', { name: 'Blast Radius' })).toBeInTheDocument()
  })

  it('describes each node to assistive tech via aria-label, and keeps it current after a data refresh', async () => {
    mockGraphCalls(() => makeGraph())
    const { client } = renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    const nodeGroups = svg.querySelectorAll('.graph-node')
    const apiGatewayNode = Array.from(nodeGroups).find((el) => el.getAttribute('aria-label')?.startsWith('api-gateway'))
    expect(apiGatewayNode?.getAttribute('aria-label')).toBe('api-gateway, healthy, critical tier')
    const visibleCircle = apiGatewayNode!.querySelector('circle.graph-node-visible')
    const initialFill = visibleCircle?.getAttribute('fill')

    // Same queryKey, same node/edge set, health flips healthy -> down: invalidating
    // (rather than changing type/team, which swaps queryKey and remounts the whole
    // graph) keeps DependencyGraph mounted so only its data-refresh effect re-runs —
    // this is the real regression test for the duplicated-logic bug (candidate 2).
    mockGraphCalls(() => makeGraph({ nodes: [
      { serviceId: 's1', name: 'api-gateway', teamId: null, tier: 'CRITICAL', healthStatus: 'UNHEALTHY' },
      { serviceId: 's2', name: 'auth-service', teamId: null, tier: 'STANDARD', healthStatus: 'DEGRADED' },
    ] }))
    await client.invalidateQueries()

    await vi.waitFor(() => {
      const refreshed = Array.from(svg.querySelectorAll('.graph-node')).find((el) =>
        el.getAttribute('aria-label')?.startsWith('api-gateway'),
      )
      expect(refreshed?.getAttribute('aria-label')).toBe('api-gateway, down, critical tier')
      expect(refreshed?.querySelector('circle.graph-node-visible')?.getAttribute('fill')).not.toBe(initialFill)
    })
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

    fireEvent.click(screen.getByRole('combobox', { name: /team filter/i }))
    fireEvent.click(await screen.findByRole('option', { name: 'Platform' }))

    expect(await screen.findByRole('group', { name: /service dependency graph/i })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(expect.stringContaining('teamId=team-1'))
  })

  it('shows a dismissible banner when the graph is truncated', async () => {
    mockGraphCalls(() => makeGraph({ truncated: true }))
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(screen.getByText(/graph capped/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByText(/graph capped/i)).not.toBeInTheDocument()
  })

  it('names cycle members outside the team-filtered graph from the registry, not raw ids', async () => {
    const cycles: Cycles = { cycles: [{ members: ['s1', 'other-team-svc'], length: 2 }], truncated: false }
    mockGraphCalls(() => makeGraph(), EMPTY_BLAST_RADIUS, cycles, EMPTY_SPOFS, [
      { id: 'other-team-svc', name: 'billing-api' },
    ])
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /1 dependency cycle/i }))

    expect(await screen.findByText(/billing-api/)).toBeInTheDocument()
    expect(screen.queryByText(/other-team-svc/)).not.toBeInTheDocument()
  })

  it('shows the cycle badge in the toolbar and marks member nodes when cycles are found', async () => {
    const cycles: Cycles = { cycles: [{ members: ['s1', 's2'], length: 2 }], truncated: false }
    mockGraphCalls(() => makeGraph(), EMPTY_BLAST_RADIUS, cycles)
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    expect(await screen.findByRole('button', { name: /1 dependency cycle/i })).toBeInTheDocument()
    const nodeGroups = svg.querySelectorAll('.graph-node')
    nodeGroups.forEach((el) => {
      expect(el.querySelector('text.graph-node-cycle-badge')?.getAttribute('opacity')).toBe('1')
      expect(el.getAttribute('aria-label')).toContain('part of a dependency cycle')
    })
  })

  it('renders no cycle badge when the tenant has no cycles', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(screen.queryByRole('button', { name: /dependency cycle/i })).not.toBeInTheDocument()
  })

  it('shows the SPOF badge in the toolbar and marks flagged nodes', async () => {
    const spofs: Spofs = {
      threshold: 5,
      rationale: 'rationale',
      items: [{ serviceId: 's1', name: 'api-gateway', teamId: null, tier: 'CRITICAL', healthStatus: 'HEALTHY', fanIn: 6, severity: 'critical' }],
    }
    mockGraphCalls(() => makeGraph(), EMPTY_BLAST_RADIUS, EMPTY_CYCLES, spofs)
    renderPage()
    const svg = await screen.findByRole('group', { name: /service dependency graph/i })

    expect(await screen.findByRole('button', { name: /1 single point of failure/i })).toBeInTheDocument()
    const nodeGroups = svg.querySelectorAll('.graph-node')
    const flagged = Array.from(nodeGroups).find((el) => el.getAttribute('aria-label')?.startsWith('api-gateway'))
    expect(flagged?.querySelector('text.graph-node-spof-badge')?.getAttribute('opacity')).toBe('1')
    expect(flagged?.getAttribute('aria-label')).toContain('a single point of failure')
    const unflagged = Array.from(nodeGroups).find((el) => el.getAttribute('aria-label')?.startsWith('auth-service'))
    expect(unflagged?.querySelector('text.graph-node-spof-badge')?.getAttribute('opacity')).toBe('0')
  })

  it('renders no SPOF badge when the tenant has no SPOFs', async () => {
    mockGraphCalls(() => makeGraph())
    renderPage()
    await screen.findByRole('group', { name: /service dependency graph/i })

    expect(screen.queryByRole('button', { name: /single point of failure/i })).not.toBeInTheDocument()
  })

  it('shows a dedicated empty state for a tenant with no services', async () => {
    mockGraphCalls(() => makeGraph({ nodes: [], edges: [] }))
    renderPage()
    expect(await screen.findByText('No services to graph yet.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /go to catalog/i })).toBeInTheDocument()
  })
})
