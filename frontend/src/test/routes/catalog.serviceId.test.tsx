import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSyncExternalStore } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/catalog.$serviceId'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'
import type { ServiceDependencies } from '#/lib/topology-types'

let mockServiceId = 'svc-1'

// Minimal reactive stand-in for TanStack Router's search-param state: real enough that
// navigate({ search }) calls re-render the component with the updated `tab`.
let mockSearch: Record<string, unknown> = {}
const searchListeners = new Set<() => void>()
function setMockSearch(next: Record<string, unknown>) {
  mockSearch = next
  searchListeners.forEach((l) => l())
}
function subscribeMockSearch(listener: () => void) {
  searchListeners.add(listener)
  return () => searchListeners.delete(listener)
}
const navigateMock = vi.fn((opts: { search: unknown }) => {
  setMockSearch(typeof opts.search === 'function' ? opts.search(mockSearch) : ((opts.search ?? {}) as Record<string, unknown>))
})

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => ({
    ...opts,
    useParams: () => ({ serviceId: mockServiceId }),
    useSearch: () => useSyncExternalStore(subscribeMockSearch, () => mockSearch),
    useNavigate: () => navigateMock,
  }),
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>{children}</a>
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
  AppLayout: ({
    children,
    actions,
  }: {
    children: React.ReactNode
    actions?: React.ReactNode
  }) => (
    <div>
      {actions}
      {children}
    </div>
  ),
}))

let mockRoles = ['ADMIN']

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: { roles: string[] } }) => unknown) =>
    selector({ user: { roles: mockRoles } }),
}))

const EMPTY_TEAMS: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }

const MOCK_TEAM: RegistryTeam = {
  id: 'team-1',
  tenantId: 't1',
  name: 'Platform',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const MOCK_SERVICE: RegistryService = {
  id: 'svc-1',
  tenantId: 't1',
  name: 'payments-api',
  description: 'Handles payments',
  teamId: null,
  repositoryUrl: null,
  techStack: ['go', 'grpc'],
  metadata: null,
  healthStatus: 'HEALTHY',
  lastDeployedAt: null,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  externalId: null,
  connectionId: null,
  source: null,
  repositoryRef: null,
  k8sCluster: 'prod-cluster',
  k8sNamespace: 'payments',
  k8sDeployment: null,
  healthEndpoint: null,
  lastCommitAt: null,
  lastCommitSha: null,
  healthCheckedAt: null,
  tier: null,
  tags: null,
  slaTarget: null,
  documentationUrl: null,
  runbookUrl: null,
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

const EMPTY_DEPENDENCIES: ServiceDependencies = { upstream: [], downstream: [] }

function mockSuccess(
  service = MOCK_SERVICE,
  myTeamIds: string[] = [],
  dependencies: ServiceDependencies = EMPTY_DEPENDENCIES,
) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/teams/mine')) return Promise.resolve(myTeamIds)
    if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
    if (path.includes('/dependencies')) return Promise.resolve(dependencies)
    return Promise.resolve(service)
  })
}

describe('ServiceDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockServiceId = 'svc-1'
    mockRoles = ['ADMIN']
    setMockSearch({})
  })

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('renders service name as heading', async () => {
    mockSuccess()
    renderPage()
    expect(await screen.findByRole('heading', { name: 'payments-api' })).toBeInTheDocument()
  })

  it('renders description', async () => {
    mockSuccess()
    renderPage()
    expect(await screen.findByText('Handles payments')).toBeInTheDocument()
  })

  it('shows health badge', async () => {
    mockSuccess()
    renderPage()
    const matches = await screen.findAllByText('healthy')
    expect(matches.length).toBeGreaterThan(0)
  })

  it('overview tab shows tech stack badges', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    expect(screen.getByRole('tab', { name: /overview/i })).toBeInTheDocument()
    expect(screen.getByText('go')).toBeInTheDocument()
    expect(screen.getByText('grpc')).toBeInTheDocument()
  })

  it('overview tab shows infrastructure details', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    expect(screen.getAllByText('prod-cluster').length).toBeGreaterThan(0)
    expect(screen.getAllByText('payments').length).toBeGreaterThan(0)
  })

  it('all three tabs are present', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    expect(screen.getByRole('tab', { name: /overview/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /contracts/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /activity/i })).toBeInTheDocument()
  })

  it('contracts tab shows placeholder', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /contracts/i }))
    expect(screen.getByText(/contract detail isn't built yet/i)).toBeInTheDocument()
  })

  it('activity tab shows placeholder', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /activity/i }))
    expect(screen.getByText(/activity detail isn't built yet/i)).toBeInTheDocument()
  })

  it('dependencies tab shows empty state when there are none', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))
    expect(await screen.findByText('No declared dependencies yet.')).toBeInTheDocument()
  })

  it('dependencies tab renders upstream and downstream entries', async () => {
    mockSuccess(MOCK_SERVICE, [], {
      downstream: [
        {
          id: 'dep-1',
          serviceId: 'svc-2',
          name: 'auth-service',
          teamId: null,
          tier: null,
          healthStatus: 'HEALTHY',
          protocol: 'HTTP',
          metadata: null,
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: '2024-01-01T00:00:00Z',
        },
      ],
      upstream: [
        {
          id: 'dep-2',
          serviceId: 'svc-3',
          name: 'storefront',
          teamId: null,
          tier: null,
          healthStatus: 'DEGRADED',
          protocol: 'GRPC',
          metadata: null,
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: '2024-01-01T00:00:00Z',
        },
      ],
    })
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))

    expect(await screen.findByRole('link', { name: 'auth-service' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'storefront' })).toBeInTheDocument()
    expect(screen.getByText('HTTP')).toBeInTheDocument()
    expect(screen.getByText('GRPC')).toBeInTheDocument()
  })

  it('dependencies tab shows error alert with traceId on fetch failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.includes('/dependencies')) {
        return Promise.reject(new ApiError('SERVER_ERROR', 'dependencies unavailable', 'trace-deps-1'))
      }
      return Promise.resolve(MOCK_SERVICE)
    })
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))

    expect(await screen.findByText(/dependencies unavailable/i)).toBeInTheDocument()
    expect(screen.getByText(/trace-deps-1/i)).toBeInTheDocument()
  })

  it('shows error alert on fetch failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.reject(new ApiError('NOT_FOUND', 'Service not found', 'trace-err'))
    })
    renderPage()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByText(/service not found/i)).toBeInTheDocument()
    expect(screen.getByText(/trace-err/i)).toBeInTheDocument()
  })

  it('has a back link pointing to /catalog', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    const link = screen.getByRole('link', { name: /service catalog/i })
    expect(link).toHaveAttribute('href', '/catalog')
  })

  it('owner team name renders as a link to /teams when service has a team', async () => {
    const ownedService = { ...MOCK_SERVICE, teamId: 'team-1' }
    const teams: PageResult<RegistryTeam> = { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 }
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(teams)
      return Promise.resolve(ownedService)
    })
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    const links = screen.getAllByRole('link', { name: 'Platform' })
    expect(links.length).toBeGreaterThan(0)
    links.forEach((l) => expect(l).toHaveAttribute('href', '/teams'))
  })

  it('admin sees the Edit button', async () => {
    mockRoles = ['ADMIN']
    mockSuccess()
    renderPage()
    expect(await screen.findByRole('button', { name: /edit/i })).toBeInTheDocument()
  })

  it('non-admin who is not a member of the owning team does not see the Edit button', async () => {
    mockRoles = ['MEMBER']
    const ownedService = { ...MOCK_SERVICE, teamId: 'team-1' }
    mockSuccess(ownedService, [])
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    expect(screen.queryByRole('button', { name: /edit/i })).not.toBeInTheDocument()
  })

  it('non-admin who is a member of the owning team sees the Edit button', async () => {
    mockRoles = ['MEMBER']
    const ownedService = { ...MOCK_SERVICE, teamId: 'team-1' }
    mockSuccess(ownedService, ['team-1'])
    renderPage()
    expect(await screen.findByRole('button', { name: /edit/i })).toBeInTheDocument()
  })

  it('non-admin does not see the Edit button for an unowned service', async () => {
    mockRoles = ['MEMBER']
    mockSuccess({ ...MOCK_SERVICE, teamId: null }, [])
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    expect(screen.queryByRole('button', { name: /edit/i })).not.toBeInTheDocument()
  })

  it('add dependency flow: search, select a target, submit, and list refreshes', async () => {
    let dependenciesCallCount = 0
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.startsWith('/v1/registry/services?search=')) {
        const page: PageResult<RegistryService> = {
          items: [{ ...MOCK_SERVICE, id: 'svc-2', name: 'auth-service' }],
          total: 1,
          limit: 20,
          offset: 0,
        }
        return Promise.resolve(page)
      }
      if (path === '/v1/topology/dependencies' && init?.method === 'POST') {
        return Promise.resolve({
          id: 'dep-1',
          sourceServiceId: 'svc-1',
          targetServiceId: 'svc-2',
          type: 'DECLARED',
          protocol: 'HTTP',
          metadata: null,
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (path.includes('/dependencies')) {
        dependenciesCallCount += 1
        if (dependenciesCallCount === 1) return Promise.resolve(EMPTY_DEPENDENCIES)
        const populated: ServiceDependencies = {
          downstream: [
            {
              id: 'dep-1',
              serviceId: 'svc-2',
              name: 'auth-service',
              teamId: null,
              tier: null,
              healthStatus: 'HEALTHY',
              protocol: 'HTTP',
              metadata: null,
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          upstream: [],
        }
        return Promise.resolve(populated)
      }
      return Promise.resolve(MOCK_SERVICE)
    })

    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))
    await screen.findByText('No declared dependencies yet.')

    fireEvent.click(screen.getByRole('button', { name: /add dependency/i }))
    const searchInput = await screen.findByPlaceholderText('Search services…')
    fireEvent.change(searchInput, { target: { value: 'auth' } })
    fireEvent.click(await screen.findByRole('button', { name: 'auth-service' }))

    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /add dependency/i }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByRole('link', { name: 'auth-service' })).toBeInTheDocument()
  })

  it('add dependency shows the API error inline in the dialog on failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.startsWith('/v1/registry/services?search=')) {
        const page: PageResult<RegistryService> = {
          items: [{ ...MOCK_SERVICE, id: 'svc-2', name: 'auth-service' }],
          total: 1,
          limit: 20,
          offset: 0,
        }
        return Promise.resolve(page)
      }
      if (path === '/v1/topology/dependencies' && init?.method === 'POST') {
        return Promise.reject(new ApiError('CONFLICT', 'A declared dependency already exists', 'trace-dup-1'))
      }
      if (path.includes('/dependencies')) return Promise.resolve(EMPTY_DEPENDENCIES)
      return Promise.resolve(MOCK_SERVICE)
    })

    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))
    await screen.findByText('No declared dependencies yet.')

    fireEvent.click(screen.getByRole('button', { name: /add dependency/i }))
    const searchInput = await screen.findByPlaceholderText('Search services…')
    fireEvent.change(searchInput, { target: { value: 'auth' } })
    fireEvent.click(await screen.findByRole('button', { name: 'auth-service' }))

    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /add dependency/i }))

    expect(await within(dialog).findByText(/already exists/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/trace-dup-1/i)).toBeInTheDocument()
  })

  it('edit dependency flow: target is locked, protocol is editable', async () => {
    let putBody: unknown = null
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path === '/v1/topology/dependencies/dep-1' && init?.method === 'PUT') {
        putBody = init.body ? JSON.parse(init.body as string) : null
        return Promise.resolve({
          id: 'dep-1',
          sourceServiceId: 'svc-1',
          targetServiceId: 'svc-2',
          type: 'DECLARED',
          protocol: 'GRPC',
          metadata: null,
          createdAt: '2024-01-01T00:00:00Z',
          updatedAt: '2024-01-01T00:00:00Z',
        })
      }
      if (path.includes('/dependencies')) {
        const populated: ServiceDependencies = {
          downstream: [
            {
              id: 'dep-1',
              serviceId: 'svc-2',
              name: 'auth-service',
              teamId: null,
              tier: null,
              healthStatus: 'HEALTHY',
              protocol: 'HTTP',
              metadata: null,
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          upstream: [],
        }
        return Promise.resolve(populated)
      }
      return Promise.resolve(MOCK_SERVICE)
    })

    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))
    await screen.findByRole('link', { name: 'auth-service' })

    fireEvent.click(screen.getByRole('button', { name: /edit dependency on auth-service/i }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByText('auth-service').length).toBeGreaterThan(0)
    expect(within(dialog).queryByPlaceholderText('Search services…')).not.toBeInTheDocument()

    fireEvent.click(within(dialog).getByRole('button', { name: /save changes/i }))

    await waitFor(() =>
      expect(putBody).toEqual({
        sourceServiceId: 'svc-1',
        targetServiceId: 'svc-2',
        protocol: 'HTTP',
        metadata: null,
      }),
    )
  })

  it('remove dependency flow: confirm dialog then delete, list refreshes', async () => {
    let deleteCalled = false
    let fetchCount = 0
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (path.includes('/v1/registry/teams/mine')) return Promise.resolve([])
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path === '/v1/topology/dependencies/dep-1' && init?.method === 'DELETE') {
        deleteCalled = true
        return Promise.resolve(undefined)
      }
      if (path.includes('/dependencies')) {
        fetchCount += 1
        const populated: ServiceDependencies = {
          downstream: [
            {
              id: 'dep-1',
              serviceId: 'svc-2',
              name: 'auth-service',
              teamId: null,
              tier: null,
              healthStatus: 'HEALTHY',
              protocol: 'HTTP',
              metadata: null,
              createdAt: '2024-01-01T00:00:00Z',
              updatedAt: '2024-01-01T00:00:00Z',
            },
          ],
          upstream: [],
        }
        return Promise.resolve(fetchCount > 1 ? EMPTY_DEPENDENCIES : populated)
      }
      return Promise.resolve(MOCK_SERVICE)
    })

    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /dependencies/i }))
    await screen.findByRole('link', { name: 'auth-service' })

    fireEvent.click(screen.getByRole('button', { name: /remove dependency on auth-service/i }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/remove dependency\?/i)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: /^remove$/i }))

    await waitFor(() => expect(deleteCalled).toBe(true))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('No declared dependencies yet.')).toBeInTheDocument()
  })
})
