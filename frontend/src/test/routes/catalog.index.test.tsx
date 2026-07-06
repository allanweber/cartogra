import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/catalog.index'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

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
  AppLayout: ({ children, description }: { children: React.ReactNode; description?: string }) => (
    <div>
      {description && <p>{description}</p>}
      {children}
    </div>
  ),
}))

vi.mock('#/components/RegisterServiceDrawer', () => ({
  RegisterServiceDrawer: () => null,
}))

vi.mock('#/components/ui/dropdown-menu', async () => {
  const { createContext, useContext, useState } = await import('react')

  type Ctx = { open: boolean; toggle: () => void }
  const Ctx = createContext<Ctx>({ open: false, toggle: () => {} })

  return {
    DropdownMenu: ({ children }: { children: React.ReactNode }) => {
      const [open, setOpen] = useState(false)
      return <Ctx.Provider value={{ open, toggle: () => setOpen((o) => !o) }}><div>{children}</div></Ctx.Provider>
    },
    DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => {
      const { toggle } = useContext(Ctx)
      return <div onClick={toggle}>{children}</div>
    },
    DropdownMenuContent: ({ children }: { children: React.ReactNode }) => {
      const { open } = useContext(Ctx)
      return open ? <div>{children}</div> : null
    },
    DropdownMenuItem: ({ children, onSelect }: { children: React.ReactNode; onSelect?: () => void }) => (
      <button onClick={onSelect}>{children}</button>
    ),
  }
})

const EMPTY_TEAMS: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }
const EMPTY_SERVICES: PageResult<RegistryService> = { items: [], total: 0, limit: 100, offset: 0 }

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

function renderPage() {
  const Page = (Route as any).component
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

function mockApiFetch(services: PageResult<RegistryService> = EMPTY_SERVICES) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
    if (path.includes('/v1/registry/services/tech-stacks')) return Promise.resolve([])
    return Promise.resolve(services)
  })
}

describe('CatalogPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0)
  })

  it('renders service name after load', async () => {
    mockApiFetch({ items: [MOCK_SERVICE], total: 1, limit: 100, offset: 0 })
    renderPage()
    expect(await screen.findByText('payments-api')).toBeInTheDocument()
  })

  it('renders service count', async () => {
    mockApiFetch({ items: [MOCK_SERVICE], total: 1, limit: 100, offset: 0 })
    renderPage()
    expect(await screen.findByText('1 service')).toBeInTheDocument()
  })

  it('renders tech stack badges on card', async () => {
    mockApiFetch({ items: [MOCK_SERVICE], total: 1, limit: 100, offset: 0 })
    renderPage()
    expect(await screen.findByText('go')).toBeInTheDocument()
    expect(screen.getByText('grpc')).toBeInTheDocument()
  })

  it('shows empty state when no services match', async () => {
    mockApiFetch(EMPTY_SERVICES)
    renderPage()
    expect(await screen.findByText(/no services match your filters/i)).toBeInTheDocument()
  })

  it('shows error alert with message on fetch failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.includes('/v1/registry/services/tech-stacks')) return Promise.resolve([])
      return Promise.reject(new ApiError('SERVER_ERROR', 'registry unavailable', 'trace-xyz'))
    })
    renderPage()
    expect(await screen.findByText(/registry unavailable/i)).toBeInTheDocument()
  })

  it('shows traceId in error alert', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/registry/teams')) return Promise.resolve(EMPTY_TEAMS)
      if (path.includes('/v1/registry/services/tech-stacks')) return Promise.resolve([])
      return Promise.reject(new ApiError('SERVER_ERROR', 'oops', 'trace-abc'))
    })
    renderPage()
    expect(await screen.findByText(/trace-abc/i)).toBeInTheDocument()
  })

  it('has a search input with correct aria-label', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    expect(screen.getByRole('textbox', { name: /search services/i })).toBeInTheDocument()
  })

  it('renders health filter controls', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Filter by health' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Healthy/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Degraded/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Down/i })).toBeInTheDocument()
  })

  it('grid view button is pressed by default', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: /grid view/i })).toHaveAttribute('aria-pressed', 'true')
  })

  it('clicking list view sets aria-pressed on list button', async () => {
    mockApiFetch({ items: [MOCK_SERVICE], total: 1, limit: 100, offset: 0 })
    renderPage()
    await screen.findByText('payments-api')
    fireEvent.click(screen.getByRole('button', { name: /list view/i }))
    expect(screen.getByRole('button', { name: /list view/i })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /grid view/i })).toHaveAttribute('aria-pressed', 'false')
  })

  it('team filter dropdown contains an Unowned option', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /filter by team/i }))
    expect(await screen.findByText('Unowned')).toBeInTheDocument()
  })

  it('selecting Unowned sends unowned=true to the API', async () => {
    mockApiFetch()
    renderPage()
    await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /filter by team/i }))
    const unownedItem = await screen.findByText('Unowned')
    fireEvent.click(unownedItem)
    await waitFor(() => {
      const calls = vi.mocked(apiFetch).mock.calls.map(([path]) => path)
      expect(calls.some((p) => p.includes('unowned=true'))).toBe(true)
    })
  })
})
