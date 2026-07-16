import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/teams'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
  Link: ({ children, to, params }: { children: React.ReactNode; to: string; params?: Record<string, string> }) => {
    const href = params ? to.replace(/\$(\w+)/g, (_, k) => params[k]) : to
    return <a href={href}>{children}</a>
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

vi.mock('#/components/TeamDialog', () => ({
  TeamDialog: ({ open, team }: { open: boolean; team?: { name: string } }) =>
    open ? <div data-testid={team ? 'manage-team-dialog' : 'create-team-dialog'}>{team?.name}</div> : null,
}))

let mockRoles = ['ADMIN']

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: { roles: string[] } }) => unknown) =>
    selector({ user: { roles: mockRoles } }),
}))

const EMPTY_TEAMS: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }
const EMPTY_SERVICES: PageResult<RegistryService> = { items: [], total: 0, limit: 1000, offset: 0 }

const MOCK_TEAM: RegistryTeam = {
  id: 'team-1',
  tenantId: 'tenant-1',
  name: 'Platform',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const MOCK_SERVICE: RegistryService = {
  id: 'svc-1',
  tenantId: 'tenant-1',
  name: 'API Gateway',
  description: null,
  teamId: 'team-1',
  repositoryUrl: null,
  techStack: null,
  metadata: null,
  healthStatus: 'HEALTHY',
  lastDeployedAt: null,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  externalId: null,
  connectionId: null,
  source: null,
  repositoryRef: null,
  k8sCluster: null,
  k8sNamespace: null,
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

const UNOWNED_SERVICE: RegistryService = { ...MOCK_SERVICE, id: 'svc-2', name: 'Orphan', teamId: null }

function renderPage() {
  const Page = (Route as any).component
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

function mockApiFetch(
  teams: PageResult<RegistryTeam> = EMPTY_TEAMS,
  services: PageResult<RegistryService> = EMPTY_SERVICES,
  myTeamIds: string[] = [],
) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/teams/mine')) return Promise.resolve(myTeamIds)
    if (path.includes('/v1/registry/teams')) return Promise.resolve(teams)
    return Promise.resolve(services)
  })
}

describe('TeamsPage', () => {
  beforeEach(() => {
    mockRoles = ['ADMIN']
    vi.clearAllMocks()
  })

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('renders team name after load', async () => {
    mockApiFetch(
      { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 },
      EMPTY_SERVICES,
    )
    renderPage()
    expect(await screen.findByText('Platform')).toBeInTheDocument()
  })

  it('shows error alert with message on fetch failure', async () => {
    vi.mocked(apiFetch).mockRejectedValue(
      new ApiError('SERVER_ERROR', 'registry unavailable', 'trace-xyz'),
    )
    renderPage()
    expect(await screen.findByText(/registry unavailable/i)).toBeInTheDocument()
  })

  it('shows traceId in error alert', async () => {
    vi.mocked(apiFetch).mockRejectedValue(
      new ApiError('SERVER_ERROR', 'boom', 'trace-abc'),
    )
    renderPage()
    expect(await screen.findByText(/trace-abc/i)).toBeInTheDocument()
  })

  it('shows empty state when no teams', async () => {
    mockApiFetch()
    renderPage()
    expect(await screen.findByText(/no teams yet/i)).toBeInTheDocument()
  })

  it('shows stat card for teams count', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    await screen.findByText('Platform')
    expect(screen.getByText('Teams')).toBeInTheDocument()
  })

  it('shows unowned services count in stat card', async () => {
    mockApiFetch(
      { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 },
      { items: [MOCK_SERVICE, UNOWNED_SERVICE], total: 2, limit: 1000, offset: 0 },
    )
    renderPage()
    await screen.findByText('Platform')
    expect(screen.getByText('Unowned Services')).toBeInTheDocument()
  })

  it('clicking a team row selects it and shows detail panel', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    const row = await screen.findByRole('button', { name: 'Platform team' })
    fireEvent.click(row)
    expect(screen.getByText('Platform Team')).toBeInTheDocument()
  })

  it('clicking a selected row closes the detail panel', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    const row = await screen.findByRole('button', { name: 'Platform team' })
    fireEvent.click(row)
    expect(screen.getByText('Platform Team')).toBeInTheDocument()
    fireEvent.click(row)
    await waitFor(() => expect(screen.queryByText('Platform Team')).not.toBeInTheDocument())
  })

  it('add team button opens create dialog', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /add team/i }))
    expect(await screen.findByTestId('create-team-dialog')).toBeInTheDocument()
  })

  it('manage button on row opens manage dialog', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    await screen.findByText('Platform')
    fireEvent.click(screen.getByTestId('manage-btn-team-1'))
    expect(await screen.findByTestId('manage-team-dialog')).toBeInTheDocument()
  })

  it('shows owned service chips in team row', async () => {
    mockApiFetch(
      { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 },
      { items: [MOCK_SERVICE], total: 1, limit: 1000, offset: 0 },
    )
    renderPage()
    expect(await screen.findByText('API Gateway')).toBeInTheDocument()
  })

  it('detail panel shows manage team button that opens dialog', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    const row = await screen.findByRole('button', { name: 'Platform team' })
    fireEvent.click(row)
    expect(screen.getByText('Platform Team')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /manage team/i }))
    expect(await screen.findByTestId('manage-team-dialog')).toBeInTheDocument()
  })

  it('detail panel close button hides panel', async () => {
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    const row = await screen.findByRole('button', { name: 'Platform team' })
    fireEvent.click(row)
    expect(screen.getByText('Platform Team')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /close panel/i }))
    await waitFor(() => expect(screen.queryByText('Platform Team')).not.toBeInTheDocument())
  })

  it('service chip in team row is a link to the service detail page', async () => {
    mockApiFetch(
      { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 },
      { items: [MOCK_SERVICE], total: 1, limit: 1000, offset: 0 },
    )
    renderPage()
    const chip = await screen.findByRole('link', { name: /api gateway/i })
    expect(chip).toHaveAttribute('href', '/catalog/svc-1')
  })

  it('detail panel service item is a link to the service detail page', async () => {
    mockApiFetch(
      { items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 },
      { items: [MOCK_SERVICE], total: 1, limit: 1000, offset: 0 },
    )
    renderPage()
    const row = await screen.findByRole('button', { name: 'Platform team' })
    fireEvent.click(row)
    const links = screen.getAllByRole('link', { name: /api gateway/i })
    expect(links.some((l) => l.getAttribute('href') === '/catalog/svc-1')).toBe(true)
  })

  it('non-admin user does not see add team button', async () => {
    mockRoles = []
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 })
    renderPage()
    await screen.findByText('Platform')
    expect(screen.queryByRole('button', { name: /add team/i })).not.toBeInTheDocument()
  })

  it('team owner who is not a member of this team does not see its manage button', async () => {
    mockRoles = ['TEAM_OWNER']
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 }, EMPTY_SERVICES, [])
    renderPage()
    await screen.findByText('Platform')
    expect(screen.queryByTestId('manage-btn-team-1')).not.toBeInTheDocument()
  })

  it('team owner who is a member of this team sees its manage button', async () => {
    mockRoles = ['TEAM_OWNER']
    mockApiFetch({ items: [MOCK_TEAM], total: 1, limit: 200, offset: 0 }, EMPTY_SERVICES, ['team-1'])
    renderPage()
    await screen.findByText('Platform')
    expect(await screen.findByTestId('manage-btn-team-1')).toBeInTheDocument()
  })

  it('team owner sees add team button', async () => {
    mockRoles = ['TEAM_OWNER']
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: /add team/i })).toBeInTheDocument()
  })

  it('team owner add team button opens create dialog', async () => {
    mockRoles = ['TEAM_OWNER']
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /add team/i }))
    expect(await screen.findByTestId('create-team-dialog')).toBeInTheDocument()
  })
})
