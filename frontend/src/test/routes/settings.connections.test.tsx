import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/settings.connections'
import { apiFetch } from '#/lib/api'
import type { PageResult } from '#/lib/registry-types'
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
  webhookEnabled: false,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const EMPTY_CONNECTIONS: PageResult<ScmConnection> = { items: [], total: 0, limit: 20, offset: 0 }
const WITH_GITHUB: PageResult<ScmConnection> = { items: [GITHUB_CONN], total: 1, limit: 20, offset: 0 }

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
    vi.mocked(apiFetch).mockResolvedValue(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    expect(screen.getByText('Azure DevOps')).toBeInTheDocument()
    expect(screen.getByText('Kubernetes')).toBeInTheDocument()
  })

  it('shows "Coming soon" badge for inactive providers', async () => {
    vi.mocked(apiFetch).mockResolvedValue(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    const badges = screen.getAllByText('Coming soon')
    expect(badges.length).toBeGreaterThan(0)
  })

  it('shows Connect button for active unconnected providers', async () => {
    vi.mocked(apiFetch).mockResolvedValue(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    const connectButtons = screen.getAllByRole('button', { name: /^connect$/i })
    expect(connectButtons.length).toBeGreaterThanOrEqual(2)
  })

  it('shows Connected badge and Manage button when GitHub is connected', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('k8s')) return Promise.resolve({ items: [], total: 0, limit: 20, offset: 0 })
      return Promise.resolve(WITH_GITHUB)
    })
    renderPage()
    await screen.findByText('Connected')
    expect(screen.getByRole('button', { name: /manage/i })).toBeInTheDocument()
  })

  it('shows sync subtitle with date when lastSyncAt is set', async () => {
    vi.mocked(apiFetch).mockResolvedValue(WITH_GITHUB)
    renderPage()
    expect(await screen.findByText(/synced/i)).toBeInTheDocument()
  })

  it('shows service count in sync subtitle', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('counts-by-connection'))
        return Promise.resolve({ counts: { 'conn-gh': 7 } })
      return Promise.resolve(WITH_GITHUB)
    })
    renderPage()
    expect(await screen.findByText(/7 services/i)).toBeInTheDocument()
  })

  it('shows singular "service" when count is 1', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('counts-by-connection'))
        return Promise.resolve({ counts: { 'conn-gh': 1 } })
      return Promise.resolve(WITH_GITHUB)
    })
    renderPage()
    expect(await screen.findByText(/\b1 service\b/)).toBeInTheDocument()
    expect(screen.queryByText(/1 services/)).not.toBeInTheDocument()
  })

  it('shows "0 services" when connection has no services', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('counts-by-connection'))
        return Promise.resolve({ counts: {} })
      return Promise.resolve(WITH_GITHUB)
    })
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
    vi.mocked(apiFetch).mockResolvedValue(noSync)
    renderPage()
    expect(await screen.findByText('Never synced')).toBeInTheDocument()
  })

  it('opens dialog when Connect is clicked', async () => {
    vi.mocked(apiFetch).mockResolvedValue(EMPTY_CONNECTIONS)
    renderPage()
    await screen.findByText('GitHub')
    fireEvent.click(screen.getAllByRole('button', { name: /^connect$/i })[0])
    await waitFor(() => expect(screen.getByTestId('dialog')).toBeInTheDocument())
  })

  it('opens dialog when Manage is clicked', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('k8s')) return Promise.resolve({ items: [], total: 0, limit: 20, offset: 0 })
      return Promise.resolve(WITH_GITHUB)
    })
    renderPage()
    await screen.findByText('Connected')
    fireEvent.click(screen.getByRole('button', { name: /manage/i }))
    await waitFor(() => expect(screen.getByTestId('dialog')).toBeInTheDocument())
  })

  it('shows error alert when connections query fails', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new Error('network error'))
    renderPage()
    expect(await screen.findByText(/failed to load connections/i)).toBeInTheDocument()
  })
})
