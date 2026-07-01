import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RegisterServiceDrawer } from '#/components/RegisterServiceDrawer'
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
    />
  ),
}))

const EMPTY_TEAMS: PageResult<RegistryTeam> = { items: [], total: 0, limit: 200, offset: 0 }

const CREATED_SERVICE: RegistryService = {
  id: 'svc-new',
  tenantId: 't1',
  name: 'new-svc',
  description: null,
  teamId: null,
  repositoryUrl: null,
  techStack: [],
  metadata: null,
  healthStatus: 'UNKNOWN',
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

function renderDrawer(props: { open?: boolean; onOpenChange?: (v: boolean) => void } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const onOpenChange = props.onOpenChange ?? vi.fn()
  return {
    onOpenChange,
    client,
    ...render(
      <QueryClientProvider client={client}>
        <RegisterServiceDrawer open={props.open ?? true} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    ),
  }
}

describe('RegisterServiceDrawer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the dialog title', () => {
    vi.mocked(apiFetch).mockResolvedValue(EMPTY_TEAMS)
    renderDrawer()
    expect(screen.getByRole('heading', { name: 'Register service' })).toBeInTheDocument()
  })

  it('POST is called with name and techStack on submit', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.resolve(CREATED_SERVICE)
    })
    renderDrawer()

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'my-svc' } })
    const tagsInput = screen.getByTestId('tags-input')
    fireEvent.change(tagsInput, { target: { value: 'go,postgres' } })

    fireEvent.click(screen.getByRole('button', { name: /register service/i }))

    await waitFor(() => {
      const postCall = vi.mocked(apiFetch).mock.calls.find(
        ([_, init]) => init?.method === 'POST',
      )
      expect(postCall).toBeDefined()
      const body = JSON.parse((postCall![1] as RequestInit).body as string)
      expect(body.name).toBe('my-svc')
      expect(body.techStack).toEqual(['go', 'postgres'])
    })
  })

  it('POST sends null for empty optional fields', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.resolve(CREATED_SERVICE)
    })
    renderDrawer()

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'bare-svc' } })
    fireEvent.click(screen.getByRole('button', { name: /register service/i }))

    await waitFor(() => {
      const postCall = vi.mocked(apiFetch).mock.calls.find(
        ([_, init]) => init?.method === 'POST',
      )
      expect(postCall).toBeDefined()
      const body = JSON.parse((postCall![1] as RequestInit).body as string)
      expect(body.description).toBeNull()
      expect(body.teamId).toBeNull()
      expect(body.repositoryUrl).toBeNull()
    })
  })

  it('displays error with traceId when POST fails', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.reject(new ApiError('CONFLICT', 'Name already exists', 'trace-reg-1'))
    })
    renderDrawer()

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'dup-svc' } })
    fireEvent.click(screen.getByRole('button', { name: /register service/i }))

    expect(await screen.findByText(/Name already exists/)).toBeInTheDocument()
    expect(await screen.findByText(/trace-reg-1/)).toBeInTheDocument()
  })

  it('calls onOpenChange(false) after successful registration', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.resolve(CREATED_SERVICE)
    })
    const onOpenChange = vi.fn()
    renderDrawer({ onOpenChange })

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'new-svc' } })
    fireEvent.click(screen.getByRole('button', { name: /register service/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('error clears when drawer is closed and reopened', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.reject(new ApiError('SERVER_ERROR', 'kaboom', 'trace-2'))
    })
    const onOpenChange = vi.fn()
    const { rerender, client } = renderDrawer({ onOpenChange })

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'svc' } })
    fireEvent.submit(screen.getByRole('button', { name: /register service/i }).closest('form')!)
    expect(await screen.findByText(/kaboom/)).toBeInTheDocument()

    rerender(
      <QueryClientProvider client={client}>
        <RegisterServiceDrawer open={false} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={client}>
        <RegisterServiceDrawer open={true} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )

    expect(screen.queryByText('kaboom')).not.toBeInTheDocument()
  })

  it('form fields are empty when drawer is closed and reopened', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/teams')) return Promise.resolve(EMPTY_TEAMS)
      return Promise.resolve(CREATED_SERVICE)
    })
    const onOpenChange = vi.fn()
    const { rerender, client } = renderDrawer({ onOpenChange })

    fireEvent.change(screen.getByPlaceholderText('e.g. payments-api'), { target: { value: 'typed-name' } })
    expect(screen.getByDisplayValue('typed-name')).toBeInTheDocument()

    rerender(
      <QueryClientProvider client={client}>
        <RegisterServiceDrawer open={false} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )
    rerender(
      <QueryClientProvider client={client}>
        <RegisterServiceDrawer open={true} onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    )

    expect(screen.queryByDisplayValue('typed-name')).not.toBeInTheDocument()
  })
})
