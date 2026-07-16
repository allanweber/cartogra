import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/settings.connections'
import { apiFetch } from '#/lib/api'
import type { PageResult, TenantInfo } from '#/lib/registry-types'
import type { ScmConnection } from '#/components/ScmConnectionDialog'

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

vi.mock('#/components/SettingsTabsLayout', () => ({
  SettingsTabsLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('#/components/ScmConnectionDialog', async () => {
  const actual = await vi.importActual<typeof import('#/components/ScmConnectionDialog')>(
    '#/components/ScmConnectionDialog',
  )
  return {
    ...actual,
    ScmConnectionDialog: ({ open, provider }: { open: boolean; provider: string }) =>
      open ? <div data-testid="dialog">{provider}</div> : null,
  }
})

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

const GITHUB_CONN: ScmConnection = {
  id: 'conn-gh',
  tenantId: 'tenant-1',
  provider: 'github',
  config: { org: 'my-org' },
  syncScheduler: true,
  pollIntervalMinutes: 15,
  nextSyncAt: null,
  lastSyncAt: '2024-06-01T10:00:00Z',
  lastSyncStatus: 'SUCCESS',
  lastSyncError: null,
  webhookEnabled: false,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const EMPTY_CONNECTIONS: PageResult<ScmConnection> = { items: [], total: 0, limit: 20, offset: 0 }
const WITH_GITHUB: PageResult<ScmConnection> = { items: [GITHUB_CONN], total: 1, limit: 20, offset: 0 }
const EMPTY_CLUSTERS = { items: [], total: 0, limit: 20, offset: 0 }
const MOCK_TENANT: TenantInfo = {
  id: 'tenant-1',
  name: 'Acme',
  slug: 'acme',
  plan: {
    name: 'pro',
    slug: 'pro',
    maxServices: -1,
    maxUsers: -1,
    maxApiKeys: -1,
    maxScmConnections: -1,
    maxK8sClusters: -1,
    ssoEnabled: false,
    rateLimitReplenish: 100,
    rateLimitBurst: 200,
  },
  usersUsed: 1,
  createdAt: '2024-01-01T00:00:00Z',
}

function mockApiFetch(
  connectionsResponse: PageResult<ScmConnection> = EMPTY_CONNECTIONS,
  counts: Record<string, number> = {},
) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/auth/tenant')) return Promise.resolve(MOCK_TENANT)
    if (path.includes('k8s')) return Promise.resolve(EMPTY_CLUSTERS)
    if (path.includes('counts-by-connection')) return Promise.resolve({ counts })
    return Promise.resolve(connectionsResponse)
  })
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const Page = (Route as any).component
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('ConnectionsPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders all provider names', async () => {
    mockApiFetch(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    expect(screen.getByText('Azure DevOps')).toBeInTheDocument()
    expect(screen.getByText('Kubernetes')).toBeInTheDocument()
  })

  it('shows "Coming soon" badge for inactive providers', async () => {
    mockApiFetch(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    const badges = screen.getAllByText('Coming soon')
    expect(badges.length).toBeGreaterThan(0)
  })

  it('shows Connect button for active unconnected providers', async () => {
    mockApiFetch(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    const connectButtons = screen.getAllByRole('button', { name: /^connect$/i })
    expect(connectButtons.length).toBeGreaterThanOrEqual(2)
  })

  it('shows Connected badge and Manage button when GitHub is connected', async () => {
    mockApiFetch(WITH_GITHUB)
    renderPage()
    await screen.findByText('Connected')
    expect(screen.getByRole('button', { name: /manage/i })).toBeInTheDocument()
  })

  it('shows sync subtitle with date when lastSyncAt is set', async () => {
    mockApiFetch(WITH_GITHUB)
    renderPage()
    expect(await screen.findByText(/synced/i)).toBeInTheDocument()
  })

  it('shows service count in sync subtitle', async () => {
    mockApiFetch(WITH_GITHUB, { 'conn-gh': 7 })
    renderPage()
    expect(await screen.findByText(/7 services/i)).toBeInTheDocument()
  })

  it('shows singular "service" when count is 1', async () => {
    mockApiFetch(WITH_GITHUB, { 'conn-gh': 1 })
    renderPage()
    expect(await screen.findByText(/\b1 service\b/)).toBeInTheDocument()
    expect(screen.queryByText(/1 services/)).not.toBeInTheDocument()
  })

  it('shows "0 services" when connection has no services', async () => {
    mockApiFetch(WITH_GITHUB, {})
    renderPage()
    expect(await screen.findByText(/0 services/i)).toBeInTheDocument()
  })

  it('shows "Never synced" when lastSyncAt is null', async () => {
    const noSync: PageResult<ScmConnection> = {
      items: [{ ...GITHUB_CONN, lastSyncAt: null }],
      total: 1,
      limit: 20,
      offset: 0,
    }
    mockApiFetch(noSync)
    renderPage()
    expect(await screen.findByText('Never synced')).toBeInTheDocument()
  })

  it('shows "Sync failed" badge instead of "Connected" when last sync failed', async () => {
    const failed: PageResult<ScmConnection> = {
      items: [{ ...GITHUB_CONN, lastSyncStatus: 'FAILED', lastSyncError: 'GitHub API error listing repos: 401 UNAUTHORIZED' }],
      total: 1,
      limit: 20,
      offset: 0,
    }
    mockApiFetch(failed)
    renderPage()
    expect(await screen.findByText('Sync failed')).toBeInTheDocument()
    expect(screen.queryByText('Connected')).not.toBeInTheDocument()
  })

  it('shows a banner alert when a connection failed to sync', async () => {
    const failed: PageResult<ScmConnection> = {
      items: [{ ...GITHUB_CONN, lastSyncStatus: 'FAILED', lastSyncError: 'GitHub API error listing repos: 401 UNAUTHORIZED' }],
      total: 1,
      limit: 20,
      offset: 0,
    }
    mockApiFetch(failed)
    renderPage()
    expect(await screen.findByText(/GitHub sync failed/i)).toBeInTheDocument()
    expect(screen.getAllByText(/401 UNAUTHORIZED/).length).toBeGreaterThan(0)
  })

  it('opens dialog when Connect is clicked', async () => {
    mockApiFetch(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    fireEvent.click(screen.getAllByRole('button', { name: /^connect$/i })[0])
    await waitFor(() => expect(screen.getByTestId('dialog')).toBeInTheDocument())
  })

  it('opens dialog when Manage is clicked', async () => {
    mockApiFetch(WITH_GITHUB)
    renderPage()
    await screen.findByText('Connected')
    fireEvent.click(screen.getByRole('button', { name: /manage/i }))
    await waitFor(() => expect(screen.getByTestId('dialog')).toBeInTheDocument())
  })

  it('shows error alert when connections query fails', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new Error('network error'))
    renderPage()
    expect(await screen.findByText(/network error/i)).toBeInTheDocument()
  })

  it('shows traceId in error alert when connections query fails with ApiError', async () => {
    const { ApiError } = await import('#/lib/api')
    vi.mocked(apiFetch).mockRejectedValue(
      new ApiError('INTERNAL_ERROR', 'Failed to load connections', 'abc123traceid'),
    )
    renderPage()
    expect(await screen.findByText(/Failed to load connections/i)).toBeInTheDocument()
    expect(await screen.findByText(/abc123traceid/)).toBeInTheDocument()
  })
})
