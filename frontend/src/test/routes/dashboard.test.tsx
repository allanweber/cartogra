import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/dashboard'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService } from '#/lib/registry-types'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
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

function makeService(overrides: Partial<RegistryService>): RegistryService {
  return {
    id: 'svc-1',
    tenantId: 't1',
    name: 'payments-api',
    description: null,
    teamId: null,
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
    ...overrides,
  }
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

describe('DashboardPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('shows error alert with traceId on fetch failure', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('SERVER_ERROR', 'registry unavailable', 'trace-dash-1'))
    renderPage()
    // Both the health-overview card and the stale-services card render their own
    // error Alert on failure, so multiple matches are expected here.
    expect((await screen.findAllByText(/registry unavailable/i)).length).toBeGreaterThan(0)
    expect((await screen.findAllByText(/trace-dash-1/i)).length).toBeGreaterThan(0)
  })

  it('renders health score and counts derived from live services, not mock data', async () => {
    const page: PageResult<RegistryService> = {
      items: [
        makeService({ id: 's1', healthStatus: 'HEALTHY', tier: 'CRITICAL' }),
        makeService({ id: 's2', healthStatus: 'DEGRADED' }),
        makeService({ id: 's3', healthStatus: 'UNHEALTHY', teamId: null }),
      ],
      total: 3,
      limit: 200,
      offset: 0,
    }
    vi.mocked(apiFetch).mockResolvedValue(page)
    renderPage()

    // 1 healthy of 3 -> 33%
    expect(await screen.findByText('33%')).toBeInTheDocument()
    expect(screen.getByText(/1 of 3 healthy/)).toBeInTheDocument()
  })

  it('flags a service as stale when lastDeployedAt is older than 14 days', async () => {
    const oldDate = new Date(Date.now() - 20 * 86400 * 1000).toISOString()
    const page: PageResult<RegistryService> = {
      items: [makeService({ id: 's1', name: 'stale-svc', lastDeployedAt: oldDate })],
      total: 1,
      limit: 200,
      offset: 0,
    }
    vi.mocked(apiFetch).mockResolvedValue(page)
    renderPage()

    expect(await screen.findByText('stale-svc')).toBeInTheDocument()
  })

  it('shows "No stale services" when nothing is stale', async () => {
    const page: PageResult<RegistryService> = {
      items: [makeService({ id: 's1', lastDeployedAt: new Date().toISOString() })],
      total: 1,
      limit: 200,
      offset: 0,
    }
    vi.mocked(apiFetch).mockResolvedValue(page)
    renderPage()

    expect(await screen.findByText('No stale services.')).toBeInTheDocument()
  })
})
