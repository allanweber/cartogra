import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { TeamDialog } from '#/components/TeamDialog'
import { apiFetch, ApiError } from '#/lib/api'

import type { PageResult, RegistryService, RegistryTeam, TenantUser } from '#/lib/registry-types'

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

let mockRoles = ['ADMIN']

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: { roles: string[] } }) => unknown) =>
    selector({ user: { roles: mockRoles } }),
}))

const MOCK_TEAM: RegistryTeam = {
  id: 'team-1',
  tenantId: 'tenant-1',
  name: 'Platform',
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const UPDATED_TEAM: RegistryTeam = { ...MOCK_TEAM, name: 'Platform v2' }

const OWNED_SERVICE: RegistryService = {
  id: 'svc-1',
  tenantId: 'tenant-1',
  name: 'API Gateway',
  description: null,
  teamId: 'team-1',
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

const ORPHAN_SERVICE: RegistryService = {
  ...OWNED_SERVICE,
  id: 'svc-2',
  name: 'Orphan Worker',
  teamId: null,
}

const EMPTY_SERVICES: PageResult<RegistryService> = { items: [], total: 0, limit: 1000, offset: 0 }
const WITH_SERVICES: PageResult<RegistryService> = {
  items: [OWNED_SERVICE, ORPHAN_SERVICE],
  total: 2,
  limit: 1000,
  offset: 0,
}

const TENANT_USERS: TenantUser[] = [
  { id: 'user-1', email: 'ada@cartogra.io', name: 'Ada Lovelace', roles: ['MEMBER'], disabled: false },
  { id: 'user-2', email: 'grace@cartogra.io', name: null, roles: ['VIEWER'], disabled: false },
]

function mockEditFetch(
  services: PageResult<RegistryService> = EMPTY_SERVICES,
  teamResult: RegistryTeam = UPDATED_TEAM,
) {
  vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
    if (!init?.method || init.method === 'GET') {
      if (path.includes('/members')) return Promise.resolve([])
      if (path.includes('/admin/users')) return Promise.resolve(TENANT_USERS)
      return Promise.resolve(services)
    }
    return Promise.resolve(teamResult)
  })
}

function renderDialog(props: {
  team?: RegistryTeam
  open?: boolean
  onOpenChange?: (v: boolean) => void
  onDeleted?: () => void
} = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onOpenChange = props.onOpenChange ?? vi.fn()
  const onDeleted = props.onDeleted ?? vi.fn()
  return {
    onOpenChange,
    onDeleted,
    ...render(
      <QueryClientProvider client={client}>
        <TeamDialog
          team={props.team}
          open={props.open ?? true}
          onOpenChange={onOpenChange}
          onDeleted={onDeleted}
        />
      </QueryClientProvider>,
    ),
  }
}

// ─── Create mode ──────────────────────────────────────────────────────────────

describe('TeamDialog — create mode', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows "Add team" title', () => {
    renderDialog()
    expect(screen.getByText('Add team')).toBeInTheDocument()
  })

  it('renders an empty name input', () => {
    renderDialog()
    expect(screen.getByRole('textbox')).toHaveValue('')
  })

  it('does not show Delete button in create mode', () => {
    renderDialog()
    expect(screen.queryByRole('button', { name: /^delete$/i })).not.toBeInTheDocument()
  })

  it('does not show services section in create mode', () => {
    renderDialog()
    expect(screen.queryByText('Services')).not.toBeInTheDocument()
  })

  it('POST /v1/registry/teams with trimmed name on submit', async () => {
    vi.mocked(apiFetch).mockResolvedValue(MOCK_TEAM)
    renderDialog()

    fireEvent.change(screen.getByRole('textbox'), { target: { value: '  Platform  ' } })
    fireEvent.click(screen.getByRole('button', { name: /create team/i }))

    await waitFor(() => {
      const postCall = vi.mocked(apiFetch).mock.calls.find(([_, init]) => init?.method === 'POST')
      expect(postCall).toBeDefined()
      expect(JSON.parse((postCall![1] as RequestInit).body as string).name).toBe('Platform')
    })
  })

  it('calls onOpenChange(false) after successful create', async () => {
    vi.mocked(apiFetch).mockResolvedValue(MOCK_TEAM)
    const { onOpenChange } = renderDialog()

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Platform' } })
    fireEvent.click(screen.getByRole('button', { name: /create team/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('shows error alert with traceId on create failure', async () => {
    vi.mocked(apiFetch).mockRejectedValue(
      new ApiError('CONFLICT', 'Name already taken', 'trace-c1'),
    )
    renderDialog()

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Platform' } })
    fireEvent.click(screen.getByRole('button', { name: /create team/i }))

    expect(await screen.findByText(/name already taken/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-c1/)).toBeInTheDocument()
  })

  it('shows pending state while creating', async () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))
    renderDialog()

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'Platform' } })
    fireEvent.click(screen.getByRole('button', { name: /create team/i }))

    expect(await screen.findByText(/creating/i)).toBeInTheDocument()
  })

  it('cancel button calls onOpenChange(false)', () => {
    const { onOpenChange } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('does not render when closed', () => {
    renderDialog({ open: false })
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })
})

// ─── Edit mode ────────────────────────────────────────────────────────────────

describe('TeamDialog — edit mode', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockRoles = ['ADMIN']
  })

  it('shows team name as title', () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    expect(screen.getByText('Platform')).toBeInTheDocument()
  })

  it('pre-fills name input with team name', () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    expect(screen.getByDisplayValue('Platform')).toBeInTheDocument()
  })

  it('shows "Save changes" submit button', () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    expect(screen.getByRole('button', { name: /save changes/i })).toBeInTheDocument()
  })

  it('shows services section with filter input', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    expect(await screen.findByText('Services')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /filter services/i })).toBeInTheDocument()
  })

  it('pre-checks owned services', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    const checkbox = await screen.findByRole('checkbox', { name: 'API Gateway' })
    expect(checkbox).toBeChecked()
  })

  it('orphan services are unchecked', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    const checkbox = await screen.findByRole('checkbox', { name: 'Orphan Worker' })
    expect(checkbox).not.toBeChecked()
  })

  it('checking an orphan service marks it "will assign"', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    const checkbox = await screen.findByRole('checkbox', { name: 'Orphan Worker' })
    fireEvent.click(checkbox)
    expect(screen.getByText('will assign')).toBeInTheDocument()
  })

  it('unchecking an owned service marks it "will orphan"', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    const checkbox = await screen.findByRole('checkbox', { name: 'API Gateway' })
    fireEvent.click(checkbox)
    expect(screen.getByText('will orphan')).toBeInTheDocument()
  })

  it('filter narrows the visible services list', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })
    await screen.findByRole('checkbox', { name: 'API Gateway' })
    fireEvent.change(screen.getByRole('textbox', { name: /filter services/i }), {
      target: { value: 'orphan' },
    })
    expect(screen.queryByRole('checkbox', { name: 'API Gateway' })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Orphan Worker' })).toBeInTheDocument()
  })

  it('PUT /v1/registry/teams/:id with updated name', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })

    fireEvent.change(screen.getByDisplayValue('Platform'), { target: { value: 'Platform v2' } })
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const putCall = vi.mocked(apiFetch).mock.calls.find(
        ([path, init]) => init?.method === 'PUT' && path.includes('team-1'),
      )
      expect(putCall).toBeDefined()
      expect(JSON.parse((putCall![1] as RequestInit).body as string).name).toBe('Platform v2')
    })
  })

  it('PATCH /v1/registry/services/:id/owner when assigning an orphan', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })

    const checkbox = await screen.findByRole('checkbox', { name: 'Orphan Worker' })
    fireEvent.click(checkbox)
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const patchCall = vi.mocked(apiFetch).mock.calls.find(
        ([path, init]) => init?.method === 'PATCH' && path.includes('svc-2'),
      )
      expect(patchCall).toBeDefined()
      expect(JSON.parse((patchCall![1] as RequestInit).body as string).teamId).toBe('team-1')
    })
  })

  it('DELETE /v1/registry/services/:id/owner when unassigning an owned service', async () => {
    mockEditFetch(WITH_SERVICES)
    renderDialog({ team: MOCK_TEAM })

    const checkbox = await screen.findByRole('checkbox', { name: 'API Gateway' })
    fireEvent.click(checkbox)
    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => {
      const deleteCall = vi.mocked(apiFetch).mock.calls.find(
        ([path, init]) => init?.method === 'DELETE' && path.includes('svc-1/owner'),
      )
      expect(deleteCall).toBeDefined()
    })
  })

  it('calls onOpenChange(false) after successful save', async () => {
    mockEditFetch()
    const { onOpenChange } = renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('shows error alert with traceId on save failure', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        return Promise.resolve(path.includes('/members') ? [] : EMPTY_SERVICES)
      }
      return Promise.reject(new ApiError('CONFLICT', 'Name already taken', 'trace-e1'))
    })
    renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect(await screen.findByText(/name already taken/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-e1/)).toBeInTheDocument()
  })

  it('shows pending state while saving', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        return Promise.resolve(path.includes('/members') ? [] : EMPTY_SERVICES)
      }
      return new Promise(() => {})
    })
    renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    expect(await screen.findByText(/saving/i)).toBeInTheDocument()
  })

  it('delete requires confirmation step', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }))
    expect(await screen.findByRole('button', { name: /confirm delete/i })).toBeInTheDocument()
  })

  it('DELETE /v1/registry/teams/:id on confirmed delete', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        return Promise.resolve(path.includes('/members') ? [] : EMPTY_SERVICES)
      }
      return Promise.resolve(undefined)
    })
    renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }))
    await screen.findByRole('button', { name: /confirm delete/i })
    fireEvent.click(screen.getByRole('button', { name: /confirm delete/i }))

    await waitFor(() => {
      const deleteCall = vi.mocked(apiFetch).mock.calls.find(
        ([path, init]) => init?.method === 'DELETE' && path.includes('team-1') && !path.includes('owner'),
      )
      expect(deleteCall).toBeDefined()
    })
  })

  it('calls onDeleted after successful delete', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        return Promise.resolve(path.includes('/members') ? [] : EMPTY_SERVICES)
      }
      return Promise.resolve(undefined)
    })
    const { onDeleted } = renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }))
    await screen.findByRole('button', { name: /confirm delete/i })
    fireEvent.click(screen.getByRole('button', { name: /confirm delete/i }))

    await waitFor(() => expect(onDeleted).toHaveBeenCalled())
  })

  it('"Never mind" cancels the delete confirmation', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })

    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }))
    await screen.findByRole('button', { name: /confirm delete/i })
    fireEvent.click(screen.getByRole('button', { name: /never mind/i }))

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /confirm delete/i })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument()
  })

  it('cancel button calls onOpenChange(false)', () => {
    mockEditFetch()
    const { onOpenChange } = renderDialog({ team: MOCK_TEAM })
    fireEvent.click(screen.getByRole('button', { name: /^cancel$/i }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('shows "Invite user" button in Members section for admins', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    expect(await screen.findByRole('button', { name: /invite user/i })).toBeInTheDocument()
  })

  it('hides "Invite user" button for non-admins', async () => {
    mockRoles = ['MEMBER']
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    await screen.findByText('Members')
    expect(screen.queryByRole('button', { name: /invite user/i })).not.toBeInTheDocument()
  })

  it('opens the invite dialog pre-locked to this team', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })

    fireEvent.click(await screen.findByRole('button', { name: /invite user/i }))

    await screen.findByText('Users')
    expect(screen.getAllByText('Platform').length).toBeGreaterThanOrEqual(2)
    expect(screen.queryByLabelText(/team \(optional\)/i)).not.toBeInTheDocument()
  })

  it('stages an existing user as "will add" without calling the API until save', async () => {
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })

    const search = await screen.findByPlaceholderText(/add existing user by email/i)
    fireEvent.change(search, { target: { value: 'ada' } })

    const result = await screen.findByRole('button', { name: /ada lovelace.*ada@cartogra\.io/i })
    fireEvent.click(result)

    expect(await screen.findByText(/ada lovelace/i)).toBeInTheDocument()
    expect(screen.getByText('will add')).toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith(
      '/v1/registry/teams/team-1/members',
      expect.objectContaining({ method: 'POST' }),
    )

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(
        '/v1/registry/teams/team-1/members',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ userId: 'user-1' }),
        }),
      ),
    )
  })

  it('staged member removal only calls the API after save', async () => {
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        if (path.includes('/members')) {
          return Promise.resolve([
            { id: 'm1', teamId: 'team-1', userId: 'user-1', createdAt: '2024-01-01T00:00:00Z' },
          ])
        }
        if (path.includes('/admin/users')) return Promise.resolve(TENANT_USERS)
        return Promise.resolve(EMPTY_SERVICES)
      }
      return Promise.resolve(UPDATED_TEAM)
    })
    renderDialog({ team: MOCK_TEAM })

    const removeBtn = await screen.findByRole('button', { name: /remove member/i })
    fireEvent.click(removeBtn)

    expect(screen.queryByText(/ada lovelace/i)).not.toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith(
      '/v1/registry/teams/team-1/members/user-1',
      expect.objectContaining({ method: 'DELETE' }),
    )

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }))

    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(
        '/v1/registry/teams/team-1/members/user-1',
        expect.objectContaining({ method: 'DELETE' }),
      ),
    )
  })

  it('hides the "add existing user" search for non-admins', async () => {
    mockRoles = ['MEMBER']
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    await screen.findByText('Members')
    expect(
      screen.queryByPlaceholderText(/add existing user by email/i),
    ).not.toBeInTheDocument()
  })

  it('lets a non-admin team member see members (by email) but not remove them', async () => {
    mockRoles = ['MEMBER']
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        if (path.includes('/members')) {
          return Promise.resolve([
            { id: 'm1', teamId: 'team-1', userId: 'user-1', createdAt: '2024-01-01T00:00:00Z' },
          ])
        }
        if (path.includes('/auth/users?ids=')) return Promise.resolve(TENANT_USERS)
        return Promise.resolve(EMPTY_SERVICES)
      }
      return Promise.resolve(UPDATED_TEAM)
    })
    renderDialog({ team: MOCK_TEAM })

    expect(await screen.findByText(/ada lovelace/i)).toBeInTheDocument()
    expect(screen.queryByText('user-1')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /remove member/i }),
    ).not.toBeInTheDocument()
  })

  it('a team owner sees the "add existing user" search and can remove members', async () => {
    mockRoles = ['TEAM_OWNER']
    vi.mocked(apiFetch).mockImplementation((path: string, init?: RequestInit) => {
      if (!init?.method || init.method === 'GET') {
        if (path.includes('/members')) {
          return Promise.resolve([
            { id: 'm1', teamId: 'team-1', userId: 'user-1', createdAt: '2024-01-01T00:00:00Z' },
          ])
        }
        if (path.includes('/admin/users')) return Promise.resolve(TENANT_USERS)
        return Promise.resolve(EMPTY_SERVICES)
      }
      return Promise.resolve(UPDATED_TEAM)
    })
    renderDialog({ team: MOCK_TEAM })

    expect(
      await screen.findByPlaceholderText(/add existing user by email/i),
    ).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /remove member/i })).toBeInTheDocument()
  })

  it('hides "Invite user" for a team owner (invite stays admin-only)', async () => {
    mockRoles = ['TEAM_OWNER']
    mockEditFetch()
    renderDialog({ team: MOCK_TEAM })
    await screen.findByText('Members')
    expect(screen.queryByRole('button', { name: /invite user/i })).not.toBeInTheDocument()
  })
})
