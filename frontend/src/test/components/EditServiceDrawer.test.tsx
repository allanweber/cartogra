import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EditServiceDrawer } from '#/components/EditServiceDrawer'
import { apiFetch, ApiError } from '#/lib/api'
import type { PageResult, RegistryService, RegistryTeam } from '#/lib/registry-types'

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

vi.mock('#/components/TagsInput', () => ({
  TagsInput: ({
    value,
    onChange,
    placeholder,
  }: {
    value: string[]
    onChange: (v: string[]) => void
    placeholder?: string
  }) => (
    <input
      aria-label={placeholder}
      data-testid="tags-input"
      value={value.join(',')}
      onChange={(e) => onChange(e.target.value ? e.target.value.split(',') : [])}
      readOnly={!onChange}
    />
  ),
}))

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

const MOCK_TEAMS: PageResult<RegistryTeam> = {
  items: [{ id: 'team-1', name: 'Platform', tenantId: 't1', createdAt: '', updatedAt: '' }],
  total: 1,
  limit: 200,
  offset: 0,
}

function renderDrawer(
  props: { service?: RegistryService; open?: boolean; onOpenChange?: (v: boolean) => void } = {},
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const onOpenChange = props.onOpenChange ?? vi.fn()
  return {
    onOpenChange,
    client,
    ...render(
      <QueryClientProvider client={client}>
        <EditServiceDrawer
          service={props.service ?? MOCK_SERVICE}
          open={props.open ?? true}
          onOpenChange={onOpenChange}
        />
      </QueryClientProvider>,
    ),
  }
}

function mockApiFetch(putResponse: RegistryService = MOCK_SERVICE) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path.includes('/v1/teams')) return Promise.resolve(MOCK_TEAMS)
    if (path.includes('/owner')) return Promise.resolve(putResponse)
    return Promise.resolve(putResponse)
  })
}

describe('EditServiceDrawer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders service name in title', () => {
    mockApiFetch()
    renderDrawer()
    expect(screen.getByText('payments-api')).toBeInTheDocument()
  })

  it('pre-fills name field with service name', () => {
    mockApiFetch()
    renderDrawer()
    expect(screen.getByDisplayValue('payments-api')).toBeInTheDocument()
  })

  it('pre-fills description field', () => {
    mockApiFetch()
    renderDrawer()
    expect(screen.getByDisplayValue('Handles payments')).toBeInTheDocument()
  })

  it('pre-fills techStack in tags input', () => {
    mockApiFetch()
    renderDrawer()
    expect(screen.getByDisplayValue('go,grpc')).toBeInTheDocument()
  })

  it('PUT payload includes techStack', async () => {
    mockApiFetch()
    renderDrawer()

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const calls = vi.mocked(apiFetch).mock.calls
      const putCall = calls.find(([path, init]) => init?.method === 'PUT')
      expect(putCall).toBeDefined()
      const body = JSON.parse((putCall![1] as RequestInit).body as string)
      expect(body.techStack).toEqual(['go', 'grpc'])
    })
  })

  it('does NOT call PATCH /owner when teamId is unchanged', async () => {
    const serviceWithTeam = { ...MOCK_SERVICE, teamId: 'team-1' }
    mockApiFetch(serviceWithTeam)
    renderDrawer({ service: serviceWithTeam })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const calls = vi.mocked(apiFetch).mock.calls
      const putCall = calls.find(([_, init]) => init?.method === 'PUT')
      expect(putCall).toBeDefined()
    })

    const patchCall = vi.mocked(apiFetch).mock.calls.find(([_, init]) => init?.method === 'PATCH')
    expect(patchCall).toBeUndefined()
  })

  it('calls PATCH /owner when teamId changes', async () => {
    mockApiFetch()
    renderDrawer()

    await screen.findByText('Platform')
    const select = screen.getAllByRole('combobox')[0]
    fireEvent.change(select, { target: { value: 'team-1' } })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const patchCall = vi.mocked(apiFetch).mock.calls.find(
        ([path, init]) => init?.method === 'PATCH' && path.includes('/owner'),
      )
      expect(patchCall).toBeDefined()
      const body = JSON.parse((patchCall![1] as RequestInit).body as string)
      expect(body.teamId).toBe('team-1')
    })
  })

  it('displays PUT error with traceId', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(MOCK_TEAMS)
      return Promise.reject(new ApiError('CONFLICT', 'Name already taken', 'trace-edit-1'))
    })
    renderDrawer()

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect(await screen.findByText(/Name already taken/)).toBeInTheDocument()
    expect(await screen.findByText(/trace-edit-1/)).toBeInTheDocument()
  })

  it('closes drawer on successful save', async () => {
    mockApiFetch()
    const onOpenChange = vi.fn()
    renderDrawer({ onOpenChange })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('does not close while save is in progress', async () => {
    let resolvePut!: (v: RegistryService) => void
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(MOCK_TEAMS)
      return new Promise<RegistryService>((res) => { resolvePut = res })
    })
    const onOpenChange = vi.fn()
    renderDrawer({ onOpenChange })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))
    // wait until the button shows the pending state
    await screen.findByText('Saving…')

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onOpenChange).not.toHaveBeenCalled()

    resolvePut(MOCK_SERVICE)
  })

  it('error clears when drawer is closed and reopened', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(MOCK_TEAMS)
      return Promise.reject(new ApiError('SERVER_ERROR', 'boom', 'trace-1'))
    })
    const onOpenChange = vi.fn()
    const { rerender, client } = renderDrawer({ onOpenChange })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))
    expect(await screen.findByText(/boom/)).toBeInTheDocument()

    rerender(
      <QueryClientProvider client={client}>
        <EditServiceDrawer service={MOCK_SERVICE} open={false} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={client}>
        <EditServiceDrawer service={MOCK_SERVICE} open={true} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )

    expect(screen.queryByText('boom')).not.toBeInTheDocument()
  })
})
