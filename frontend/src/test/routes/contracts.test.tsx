import { fireEvent, render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useSyncExternalStore } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Route as ListRoute } from '#/routes/_authenticated/contracts.index'
import { Route as DetailRoute } from '#/routes/_authenticated/contracts.$contractId'
import { ApiError, apiFetch } from '#/lib/api'

import type { Contract, ContractsPage, ContractVersion, ContractVersionsPage, VersionDiff } from '#/lib/contract-types'

let mockSearch: Record<string, unknown> = {}
const listeners = new Set<() => void>()
function setMockSearch(next: Record<string, unknown>) {
  mockSearch = next
  listeners.forEach((l) => l())
}
const navigateMock = vi.fn((opts: { search: unknown }) => {
  setMockSearch(
    typeof opts.search === 'function' ? opts.search(mockSearch) : ((opts.search ?? {}) as Record<string, unknown>),
  )
})

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => ({
    ...opts,
    useParams: () => ({ contractId: 'c-1' }),
    useSearch: () =>
      useSyncExternalStore(
        (l) => {
          listeners.add(l)
          return () => listeners.delete(l)
        },
        () => mockSearch,
      ),
    useNavigate: () => navigateMock,
  }),
  Link: ({ children, to, params }: { children: React.ReactNode; to: string; params?: Record<string, string> }) => (
    <a href={params ? to.replace('$contractId', params.contractId) : to}>{children}</a>
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
  AppLayout: ({ title, underDevelopment, children }: { title: string; underDevelopment?: string; children: React.ReactNode }) => (
    <div>
      <h1>{title}</h1>
      {underDevelopment && <div data-testid="under-development">{underDevelopment}</div>}
      {children}
    </div>
  ),
}))

const V1: ContractVersion = {
  id: 'v-1',
  contractId: 'c-1',
  versionNumber: 1,
  label: '1.0.0',
  specHash: 'a'.repeat(64),
  breaking: false,
  createdAt: '2026-11-01T00:00:00Z',
}
const V2: ContractVersion = {
  ...V1,
  id: 'v-2',
  versionNumber: 2,
  label: '1.1.0',
  specHash: 'b'.repeat(64),
  breaking: true,
}

const LIST: ContractsPage = {
  items: [
    { id: 'c-1', serviceId: 's-1', serviceName: 'ledger-service', latestVersion: V2, updatedAt: V2.createdAt },
    { id: 'c-2', serviceId: '0123456789ab', serviceName: null, latestVersion: V1, updatedAt: V1.createdAt },
  ],
  total: 2,
  limit: 20,
  offset: 0,
}

const CONTRACT: Contract = {
  id: 'c-1',
  serviceId: 's-1',
  serviceName: 'ledger-service',
  specType: 'openapi',
  createdAt: V1.createdAt,
  updatedAt: V2.createdAt,
  latestVersion: V2,
}

const VERSIONS: ContractVersionsPage = { items: [V2, V1], total: 2, limit: 100, offset: 0 }

const DIFF_V2: VersionDiff = {
  from: V1,
  to: V2,
  changes: [
    { op: 'changed', pointer: '/info/version', path: ['info', 'version'], before: '1.0.0', after: '1.1.0' },
    { op: 'added', pointer: '/paths/~1entries~1{id}', path: ['paths', '/entries/{id}'], before: null, after: { get: {} } },
  ],
  lines: [
    { kind: 'same', leftNo: 1, left: 'info:', rightNo: 1, right: 'info:' },
    { kind: 'changed', leftNo: 2, left: '  version: 1.0.0', rightNo: 2, right: '  version: 1.1.0' },
    { kind: 'added', leftNo: null, left: null, rightNo: 3, right: '  /entries/{id}:' },
  ],
  compatibility: {
    isBreaking: true,
    changes: [
      {
        rule: 'path-added',
        breaking: false,
        direction: 'operation',
        method: null,
        path: '/entries/{id}',
        location: null,
        message: 'Path /entries/{id} was added',
      },
      {
        rule: 'path-removed',
        breaking: true,
        direction: 'operation',
        method: null,
        path: '/entries',
        location: null,
        message: 'Path /entries was removed',
      },
    ],
  },
}

const DIFF_V1: VersionDiff = {
  from: null,
  to: V1,
  changes: [],
  lines: [{ kind: 'added', leftNo: null, left: null, rightNo: 1, right: 'info:' }],
  compatibility: { isBreaking: false, changes: [] },
}

function renderRoute(route: unknown) {
  const Page = (route as { component: React.ComponentType }).component
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

function mockApi(overrides: Record<string, unknown> = {}) {
  vi.mocked(apiFetch).mockImplementation(async (path: string) => {
    for (const [prefix, value] of Object.entries(overrides)) {
      if (path.startsWith(prefix)) {
        if (value instanceof Error) throw value
        return value
      }
    }
    if (path.startsWith('/v1/contracts?')) return LIST
    if (path === '/v1/contracts/c-1') return CONTRACT
    if (path.startsWith('/v1/contracts/c-1/versions?')) return VERSIONS
    if (path === '/v1/contracts/c-1/versions/2/diff') return DIFF_V2
    if (path === '/v1/contracts/c-1/versions/1/diff') return DIFF_V1
    throw new Error(`unexpected ${path}`)
  })
}

beforeEach(() => {
  vi.mocked(apiFetch).mockReset()
  navigateMock.mockClear()
  setMockSearch({})
})

describe('ContractsPage', () => {
  it('lists contracts from the API without the mock banner', async () => {
    mockApi()
    renderRoute(ListRoute)

    const link = await screen.findByRole('link', { name: 'ledger-service' })
    expect(link).toHaveAttribute('href', '/contracts/c-1')
    expect(screen.getByText('v2 · 1.1.0')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Unknown service 01234567' })).toBeInTheDocument()
    expect(screen.queryByTestId('under-development')).not.toBeInTheDocument()
  })

  it('sends the search term and page to the API', async () => {
    mockApi()
    setMockSearch({ q: 'ledger', page: 1 })
    renderRoute(ListRoute)

    await screen.findByRole('link', { name: 'ledger-service' })
    expect(apiFetch).toHaveBeenCalledWith('/v1/contracts?limit=20&offset=20&q=ledger')
  })

  it('shows a Status column with the verdict of the latest version', async () => {
    mockApi({
      '/v1/contracts?': {
        ...LIST,
        items: [...LIST.items, { ...LIST.items[1], id: 'c-3', latestVersion: { ...V1, breaking: null } }],
      },
    })
    renderRoute(ListRoute)

    await screen.findByRole('link', { name: 'ledger-service' })
    expect(screen.getByRole('columnheader', { name: 'Status' })).toBeInTheDocument()
    const rows = screen.getAllByRole('row').slice(1)
    expect(within(rows[0]).getByText('Breaking')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Compatible')).toBeInTheDocument()
    expect(within(rows[2]).queryByText(/Breaking|Compatible/)).not.toBeInTheDocument()
  })

  it('writes the status toggle to the URL, resets the page and sends it to the API', async () => {
    mockApi()
    setMockSearch({ q: 'ledger', page: 2 })
    renderRoute(ListRoute)

    fireEvent.click(await screen.findByRole('radio', { name: 'Breaking' }))
    expect(mockSearch).toEqual({ q: 'ledger', status: 'breaking', page: undefined })
    await screen.findByRole('link', { name: 'ledger-service' })
    expect(apiFetch).toHaveBeenCalledWith('/v1/contracts?limit=20&offset=0&q=ledger&status=breaking')

    fireEvent.click(screen.getByRole('radio', { name: 'All' }))
    expect(mockSearch.status).toBeUndefined()
  })

  it('shows the trace id when the list fails', async () => {
    mockApi({ '/v1/contracts?': new ApiError('INTERNAL_ERROR', 'Boom', 'trace-123') })
    renderRoute(ListRoute)

    expect(await screen.findByText('Boom (trace: trace-123)')).toBeInTheDocument()
  })

  it('pages when there are more contracts than fit', async () => {
    mockApi({ '/v1/contracts?': { ...LIST, total: 45 } })
    renderRoute(ListRoute)

    fireEvent.click(await screen.findByRole('button', { name: 'Next page' }))
    expect(mockSearch.page).toBe(1)
  })
})

describe('ContractDetailPage', () => {
  it('opens the latest version and renders its changes and side-by-side rows', async () => {
    mockApi()
    renderRoute(DetailRoute)

    expect(await screen.findByRole('heading', { name: 'ledger-service' })).toBeInTheDocument()
    const changes = await screen.findByRole('list', { name: 'Changes' })
    expect(within(changes).getByText('paths › /entries/{id}')).toBeInTheDocument()
    expect(within(changes).getByText('1.0.0 → 1.1.0')).toBeInTheDocument()
    const breaking = screen.getByRole('list', { name: 'Breaking changes' })
    expect(within(breaking).getByText('path-removed')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Compatible changes' })).toBeInTheDocument()
    const table = screen.getByRole('table', { name: 'Diff v1 to v2' })
    expect(table.querySelectorAll('tr[data-kind="added"]')).toHaveLength(1)
    expect(table.querySelectorAll('tr[data-kind="changed"]')).toHaveLength(1)
  })

  it('selects a version from the timeline', async () => {
    mockApi()
    renderRoute(DetailRoute)

    const timeline = await screen.findByRole('list', { name: 'Version timeline' })
    const [v2Item, v1Item] = within(timeline).getAllByRole('listitem')
    expect(within(v2Item).getByText('Breaking')).toBeInTheDocument()
    expect(within(v1Item).getByText('Compatible')).toBeInTheDocument()
    fireEvent.click(within(timeline).getByRole('button', { name: /v1/ }))

    expect(mockSearch).toEqual({ v: 1 })
    expect(await screen.findByText('Nothing to compare against: every line is new.')).toBeInTheDocument()
    expect(screen.getByRole('table', { name: 'Diff empty to v1' })).toBeInTheDocument()
  })

  it('shows the trace id when the diff fails', async () => {
    mockApi({ '/v1/contracts/c-1/versions/2/diff': new ApiError('NOT_FOUND', 'No such version', 'trace-9') })
    renderRoute(DetailRoute)

    expect(await screen.findByText('No such version (trace: trace-9)')).toBeInTheDocument()
  })
})
