import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/catalog.$serviceId'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

let mockServiceId = 'svc-1'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => ({
    ...opts,
    useParams: () => ({ serviceId: mockServiceId }),
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
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
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

function mockSuccess(service = MOCK_SERVICE) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
    return Promise.resolve(service)
  })
}

describe('ServiceDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockServiceId = 'svc-1'
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
    expect(screen.getByText(/contract data not yet available/i)).toBeInTheDocument()
  })

  it('activity tab shows placeholder', async () => {
    mockSuccess()
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    fireEvent.click(screen.getByRole('tab', { name: /activity/i }))
    expect(screen.getByText(/activity feed not yet available/i)).toBeInTheDocument()
  })

  it('shows error alert on fetch failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
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
      if (path.includes('/v1/registry/teams')) return Promise.resolve(teams)
      return Promise.resolve(ownedService)
    })
    renderPage()
    await screen.findByRole('heading', { name: 'payments-api' })
    const links = screen.getAllByRole('link', { name: 'Platform' })
    expect(links.length).toBeGreaterThan(0)
    links.forEach((l) => expect(l).toHaveAttribute('href', '/teams'))
  })
})
