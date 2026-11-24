import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/_authenticated/settings.api-keys'
import { apiFetch, apiMutate } from '#/lib/api'
import type { ApiKey, CreatedApiKey } from '#/lib/registry-types'

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

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

vi.mock('#/components/SettingsTabsLayout', () => ({
  SettingsTabsLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

let roles: string[] = ['ADMIN']
vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: { id: string; roles: string[] } }) => unknown) =>
    selector({ user: { id: 'u1', roles } }),
}))

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

const KEY: ApiKey = {
  id: 'k1',
  name: 'github-ci',
  prefix: 'cgk_AbCd1234',
  scopes: ['ci:check'],
  status: 'active',
  expiresAt: null,
  lastUsedAt: null,
  createdAt: '2026-11-01T10:00:00Z',
}

const SECRET = 'cgk_AbCd1234_the_full_secret_value'

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const Page = (Route as any).component
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

function mockList(items: ApiKey[]) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.startsWith('/v1/registry/api-keys')) {
      return Promise.resolve({ items, total: items.length, limit: 200, offset: 0 })
    }
    return Promise.resolve([])
  })
}

describe('ApiKeysPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roles = ['ADMIN']
  })

  it('viewer sees the list and no actions', async () => {
    roles = ['VIEWER']
    mockList([KEY])
    renderPage()
    await screen.findByText('github-ci')
    expect(screen.getByText('cgk_AbCd1234…')).toBeInTheDocument()
    expect(screen.getByText('active')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /create key/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /revoke/i })).not.toBeInTheDocument()
  })

  it('admin creates, sees key once, revokes', async () => {
    mockList([KEY])
    const created: CreatedApiKey = { ...KEY, id: 'k2', name: 'new-key', key: SECRET }
    vi.mocked(apiMutate).mockImplementation((_path: string, _body: unknown, method?: string) => {
      if (method === 'DELETE') return Promise.resolve(undefined as never)
      return Promise.resolve(created as never)
    })
    renderPage()
    await screen.findByText('github-ci')

    fireEvent.click(screen.getByRole('button', { name: /create key/i }))
    fireEvent.change(await screen.findByLabelText(/name/i), { target: { value: 'new-key' } })
    fireEvent.click(screen.getByRole('button', { name: /^create key$/i, hidden: false }))

    expect(await screen.findByTestId('api-key-secret')).toHaveTextContent(SECRET)
    expect(apiMutate).toHaveBeenCalledWith('/v1/registry/api-keys', {
      name: 'new-key',
      scopes: ['ci:check'],
      expiresAt: null,
    })

    fireEvent.click(screen.getByRole('button', { name: /done/i }))
    await waitFor(() => expect(screen.queryByTestId('api-key-secret')).not.toBeInTheDocument())
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^revoke$/i }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /^revoke$/i }))
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/v1/registry/api-keys/k1', undefined, 'DELETE'),
    )
  })

  it('plan limit error shown', async () => {
    mockList([])
    const { ApiError } = await import('#/lib/api')
    vi.mocked(apiMutate).mockRejectedValue(
      new ApiError('PLAN_LIMIT_EXCEEDED', 'API key limit reached for your plan.', 'trace-1'),
    )
    renderPage()
    await screen.findByText('No API keys yet.')

    fireEvent.click(screen.getByRole('button', { name: /create key/i }))
    fireEvent.change(await screen.findByLabelText(/name/i), { target: { value: 'k' } })
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: /^create key$/i }))

    expect(await screen.findByText(/API key limit reached for your plan/)).toBeInTheDocument()
    expect(screen.queryByTestId('api-key-secret')).not.toBeInTheDocument()
  })
})
