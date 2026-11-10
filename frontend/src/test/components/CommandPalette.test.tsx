import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CommandPalette } from '#/components/CommandPalette'
import { apiFetch } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

const navigateMock = vi.fn()

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')

  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

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

const MOCK_SERVICE: RegistryService = {
  id: 'a1b2c3d4-real-id',
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
}

function mockServices(page: PageResult<RegistryService>) {
  const noTeams: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }
  vi.mocked(apiFetch).mockImplementation((path: string) =>
    Promise.resolve(path.includes('/v1/registry/teams') ? noTeams : page),
  )
}

function renderPalette(open = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onOpenChange = vi.fn()
  return {
    onOpenChange,
    ...render(
      <QueryClientProvider client={client}>
        <CommandPalette open={open} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    ),
  }
}

describe('CommandPalette', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not crash while the services query is still pending', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPalette()
    expect(screen.getByPlaceholderText(/search pages, services, teams/i)).toBeInTheDocument()
  })

  it('renders fetched services in the Services group when searched', async () => {
    const page: PageResult<RegistryService> = { items: [MOCK_SERVICE], total: 1, limit: 200, offset: 0 }
    mockServices(page)
    renderPalette()

    fireEvent.change(screen.getByPlaceholderText(/search pages, services, teams/i), {
      target: { value: 'payments' },
    })

    expect(await screen.findByText('payments-api')).toBeInTheDocument()
  })

  it('lists real teams from the registry with their owned-service count, never mock teams', async () => {
    const team: RegistryTeam = { id: 'team-real-id', tenantId: 't1', name: 'Ledger', createdAt: '', updatedAt: '' }
    const services: PageResult<RegistryService> = {
      items: [{ ...MOCK_SERVICE, teamId: 'team-real-id' }],
      total: 1,
      limit: 200,
      offset: 0,
    }
    const teams: PageResult<RegistryTeam> = { items: [team], total: 1, limit: 200, offset: 0 }
    vi.mocked(apiFetch).mockImplementation((path: string) =>
      Promise.resolve(path.includes('/v1/registry/teams') ? teams : services),
    )
    renderPalette()
    const input = screen.getByPlaceholderText(/search pages, services, teams/i)

    fireEvent.change(input, { target: { value: 'Ledger' } })
    expect(await screen.findByText('Ledger')).toBeInTheDocument()
    expect(screen.getByText('1 service')).toBeInTheDocument()
    expect(screen.queryByText(/this is mock data/i)).not.toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'Sarah' } })
    expect(screen.queryByText(/Sarah K\./)).not.toBeInTheDocument()
  })

  it('navigates using the fetched service real id, not a mock id', async () => {
    const page: PageResult<RegistryService> = { items: [MOCK_SERVICE], total: 1, limit: 200, offset: 0 }
    mockServices(page)
    renderPalette()

    fireEvent.change(screen.getByPlaceholderText(/search pages, services, teams/i), {
      target: { value: 'payments' },
    })

    const item = await screen.findByText('payments-api')
    fireEvent.click(item)

    await waitFor(() => {
      expect(navigateMock).toHaveBeenCalledWith({
        to: '/catalog/$serviceId',
        params: { serviceId: 'a1b2c3d4-real-id' },
      })
    })
  })

  it('shows pages immediately regardless of query state', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPalette()
    expect(screen.getByText('Dashboard')).toBeInTheDocument()
    expect(screen.getByText('Catalog')).toBeInTheDocument()
  })
})
