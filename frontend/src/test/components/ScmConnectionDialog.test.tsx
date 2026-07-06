import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ScmConnectionDialog } from '#/components/ScmConnectionDialog'
import type { ScmConnection } from '#/components/ScmConnectionDialog'
import { apiFetch, apiMutate, ApiError } from '#/lib/api'

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

const GITHUB_CONN: ScmConnection = {
  id: 'conn-gh',
  tenantId: 'tenant-1',
  provider: 'github',
  config: { org: 'my-org', token: '***' },
  syncScheduler: true,
  pollIntervalMinutes: 30,
  nextSyncAt: null,
  lastSyncAt: '2024-01-01T12:00:00Z',
  lastSyncStatus: 'SUCCESS',
  webhookEnabled: false,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const AZURE_CONN: ScmConnection = {
  ...GITHUB_CONN,
  id: 'conn-az',
  provider: 'azuredevops',
  config: { organization: 'my-azure-org', pat: '***' },
  pollIntervalMinutes: 10,
}

function renderDialog(props: {
  open?: boolean
  provider?: 'github' | 'azuredevops'
  connection?: ScmConnection
  onOpenChange?: (v: boolean) => void
} = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onOpenChange = props.onOpenChange ?? vi.fn()
  return {
    onOpenChange,
    ...render(
      <QueryClientProvider client={client}>
        <ScmConnectionDialog
          open={props.open ?? true}
          provider={props.provider ?? 'github'}
          connection={props.connection}
          onOpenChange={onOpenChange}
        />
      </QueryClientProvider>,
    ),
  }
}

// ─── Sync interval toggle ────────────────────────────────────────────────────

describe('ScmConnectionDialog — sync interval', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders all interval options', () => {
    renderDialog()
    for (const label of ['5m', '10m', '15m', '30m', '60m']) {
      expect(screen.getByRole('radio', { name: label })).toBeInTheDocument()
    }
  })

  it('defaults to 15m on create', () => {
    renderDialog()
    expect(screen.getByRole('radio', { name: '15m' })).toHaveAttribute('data-state', 'on')
  })

  it('initialises from connection.pollIntervalMinutes on edit (GitHub 30m)', () => {
    renderDialog({ connection: GITHUB_CONN })
    expect(screen.getByRole('radio', { name: '30m' })).toHaveAttribute('data-state', 'on')
  })

  it('initialises from connection.pollIntervalMinutes on edit (Azure 10m)', () => {
    renderDialog({ provider: 'azuredevops', connection: AZURE_CONN })
    expect(screen.getByRole('radio', { name: '10m' })).toHaveAttribute('data-state', 'on')
  })

  it('selecting a different interval updates the active toggle', () => {
    renderDialog()
    fireEvent.click(screen.getByRole('radio', { name: '60m' }))
    expect(screen.getByRole('radio', { name: '60m' })).toHaveAttribute('data-state', 'on')
    expect(screen.getByRole('radio', { name: '15m' })).toHaveAttribute('data-state', 'off')
  })
})

// ─── GitHub create ────────────────────────────────────────────────────────────

describe('ScmConnectionDialog — GitHub create', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows GitHub title', () => {
    renderDialog({ provider: 'github' })
    expect(screen.getByText('GitHub')).toBeInTheDocument()
  })

  it('renders empty org and token inputs', () => {
    renderDialog({ provider: 'github' })
    expect(screen.getByPlaceholderText('Enter organization')).toHaveValue('')
    expect(screen.getByPlaceholderText('Enter access token')).toHaveValue('')
  })

  it('does not show Disconnect button in create mode', () => {
    renderDialog({ provider: 'github' })
    expect(screen.queryByRole('button', { name: /disconnect/i })).not.toBeInTheDocument()
  })

  it('POST includes provider, config with org key, and pollIntervalMinutes', async () => {
    vi.mocked(apiMutate).mockResolvedValue(GITHUB_CONN)
    renderDialog({ provider: 'github' })

    fireEvent.change(screen.getByPlaceholderText('Enter organization'), { target: { value: 'my-org' } })
    fireEvent.change(screen.getByPlaceholderText('Enter access token'), { target: { value: 'tok' } })
    fireEvent.click(screen.getByRole('radio', { name: '5m' }))
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const [path, body, method] = vi.mocked(apiMutate).mock.calls[0]
      expect(method).toBe('POST')
      expect(path).toBe('/v1/ingestion/scm-connections')
      expect((body as any).provider).toBe('github')
      expect((body as any).pollIntervalMinutes).toBe(5)
      const config = JSON.parse((body as any).config)
      expect(config.org).toBe('my-org')
      expect(config.token).toBe('tok')
    })
  })

  it('calls onOpenChange(false) after successful create', async () => {
    vi.mocked(apiMutate).mockResolvedValue(GITHUB_CONN)
    const { onOpenChange } = renderDialog({ provider: 'github' })

    fireEvent.change(screen.getByPlaceholderText('Enter organization'), { target: { value: 'org' } })
    fireEvent.change(screen.getByPlaceholderText('Enter access token'), { target: { value: 'tok' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('shows error alert with traceId on failure', async () => {
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('CONFLICT', 'Already connected', 'trace-1'))
    renderDialog({ provider: 'github' })

    fireEvent.change(screen.getByPlaceholderText('Enter organization'), { target: { value: 'org' } })
    fireEvent.change(screen.getByPlaceholderText('Enter access token'), { target: { value: 'tok' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect(await screen.findByText(/already connected/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-1/)).toBeInTheDocument()
  })
})

// ─── GitHub edit ──────────────────────────────────────────────────────────────

describe('ScmConnectionDialog — GitHub edit', () => {
  beforeEach(() => vi.clearAllMocks())

  it('pre-fills org from config.org', () => {
    renderDialog({ provider: 'github', connection: GITHUB_CONN })
    expect(screen.getByPlaceholderText('Enter organization')).toHaveValue('my-org')
  })

  it('token field is empty with placeholder on edit', () => {
    renderDialog({ provider: 'github', connection: GITHUB_CONN })
    expect(screen.getByPlaceholderText(/leave blank to keep/i)).toHaveValue('')
  })

  it('shows last sync status badge', () => {
    renderDialog({ provider: 'github', connection: GITHUB_CONN })
    expect(screen.getByText('SUCCESS')).toBeInTheDocument()
  })

  it('PUT includes config with org key and pollIntervalMinutes', async () => {
    vi.mocked(apiMutate).mockResolvedValue(GITHUB_CONN)
    renderDialog({ provider: 'github', connection: GITHUB_CONN })

    fireEvent.change(screen.getByPlaceholderText('Enter organization'), { target: { value: 'updated-org' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const [path, body, method] = vi.mocked(apiMutate).mock.calls[0]
      expect(method).toBe('PUT')
      expect(path).toContain('conn-gh')
      expect((body as any).pollIntervalMinutes).toBe(30)
      const config = JSON.parse((body as any).config)
      expect(config.org).toBe('updated-org')
    })
  })

  it('disconnect requires confirmation', () => {
    renderDialog({ provider: 'github', connection: GITHUB_CONN })
    fireEvent.click(screen.getByRole('button', { name: /disconnect/i }))
    expect(screen.getByText('Disconnect?')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /confirm/i })).toBeInTheDocument()
  })

  it('confirmed disconnect calls DELETE', async () => {
    vi.mocked(apiFetch).mockResolvedValue(undefined)
    renderDialog({ provider: 'github', connection: GITHUB_CONN })

    fireEvent.click(screen.getByRole('button', { name: /disconnect/i }))
    await screen.findByRole('button', { name: /confirm/i })
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }))

    await waitFor(() => {
      const [path, init] = vi.mocked(apiFetch).mock.calls[0]
      expect((init as RequestInit).method).toBe('DELETE')
      expect(path).toContain('conn-gh')
    })
  })

  it('"Never mind" cancels the disconnect confirmation', async () => {
    renderDialog({ provider: 'github', connection: GITHUB_CONN })
    fireEvent.click(screen.getByRole('button', { name: /disconnect/i }))
    await screen.findByRole('button', { name: /confirm/i })
    fireEvent.click(screen.getByRole('button', { name: /never mind/i }))

    await waitFor(() =>
      expect(screen.queryByText('Disconnect?')).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: /disconnect/i })).toBeInTheDocument()
  })
})

// ─── Azure DevOps edit ────────────────────────────────────────────────────────

describe('ScmConnectionDialog — Azure DevOps', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows Azure DevOps title', () => {
    renderDialog({ provider: 'azuredevops' })
    expect(screen.getByText('Azure DevOps')).toBeInTheDocument()
  })

  it('pre-fills organization from config.organization', () => {
    renderDialog({ provider: 'azuredevops', connection: AZURE_CONN })
    expect(screen.getByPlaceholderText('Enter organization name')).toHaveValue('my-azure-org')
  })

  it('PUT uses organization key (not org) in config', async () => {
    vi.mocked(apiMutate).mockResolvedValue(AZURE_CONN)
    renderDialog({ provider: 'azuredevops', connection: AZURE_CONN })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const [, body] = vi.mocked(apiMutate).mock.calls[0]
      const config = JSON.parse((body as any).config)
      expect(config.organization).toBe('my-azure-org')
      expect(config).not.toHaveProperty('org')
    })
  })

  it('POST uses organization key in config', async () => {
    vi.mocked(apiMutate).mockResolvedValue(AZURE_CONN)
    renderDialog({ provider: 'azuredevops' })

    fireEvent.change(screen.getByPlaceholderText('Enter organization name'), { target: { value: 'new-org' } })
    fireEvent.change(screen.getByPlaceholderText('Enter personal access token'), { target: { value: 'pat' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const [, body] = vi.mocked(apiMutate).mock.calls[0]
      const config = JSON.parse((body as any).config)
      expect(config.organization).toBe('new-org')
      expect(config.pat).toBe('pat')
      expect(config).not.toHaveProperty('org')
    })
  })
})
