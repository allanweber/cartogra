import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/settings.users'
import { apiFetch, apiMutate } from '#/lib/api'
import type { TenantUser } from '#/lib/registry-types'

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

const mockToastSuccess = vi.fn()
const mockToastError = vi.fn()
vi.mock('sonner', () => ({
  toast: { success: (...args: unknown[]) => mockToastSuccess(...args), error: (...args: unknown[]) => mockToastError(...args) },
}))

vi.mock('#/components/SettingsTabsLayout', () => ({
  SettingsTabsLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('#/components/InviteUserDialog', () => ({
  InviteUserDialog: ({ open }: { open: boolean }) => (open ? <div data-testid="invite-dialog" /> : null),
}))

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: { id: string } }) => unknown) => selector({ user: { id: 'user-self' } }),
}))

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

const USERS: TenantUser[] = [
  { id: 'user-self', email: 'me@acme.com', name: 'Me', roles: ['ADMIN'], disabled: false },
  { id: 'user-other', email: 'other@acme.com', name: 'Other Person', roles: ['MEMBER'], disabled: false },
]

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

describe('UsersPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists tenant users', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('other@acme.com')
    expect(screen.getByText('me@acme.com')).toBeInTheDocument()
  })

  it('marks the current user with a "You" badge', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('You')
  })

  it('disables role select and remove button for the current user', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('other@acme.com')
    const selects = screen.getAllByRole('combobox')
    const removeButtons = screen.getAllByRole('button', { name: /^remove$/i })
    expect(selects[0]).toBeDisabled()
    expect(removeButtons[0]).toBeDisabled()
    expect(selects[1]).not.toBeDisabled()
    expect(removeButtons[1]).not.toBeDisabled()
  })

  it('opens invite dialog when Invite user is clicked', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('other@acme.com')
    fireEvent.click(screen.getByRole('button', { name: /invite user/i }))
    await waitFor(() => expect(screen.getByTestId('invite-dialog')).toBeInTheDocument())
  })

  it('changes a role via the select and shows a success toast', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    vi.mocked(apiMutate).mockResolvedValue({ ...USERS[1], roles: ['ADMIN'] })
    renderPage()
    await screen.findByText('other@acme.com')
    const selects = screen.getAllByRole('combobox')
    fireEvent.change(selects[1], { target: { value: 'ADMIN' } })
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/auth/admin/users/user-other/role', { role: 'ADMIN' }, 'PATCH'),
    )
    await waitFor(() => expect(mockToastSuccess).toHaveBeenCalledWith('Role updated'))
  })

  it('requires confirmation before removing a user', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('other@acme.com')
    const removeButtons = screen.getAllByRole('button', { name: /^remove$/i })
    fireEvent.click(removeButtons[1])
    expect(apiMutate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /confirm/i }))
    await waitFor(() => expect(apiMutate).toHaveBeenCalledWith('/auth/admin/users/user-other', undefined, 'DELETE'))
  })

  it('shows error toast when role update fails', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    vi.mocked(apiMutate).mockRejectedValue(new Error('Cannot change role: this is the tenant\'s last admin.'))
    renderPage()
    await screen.findByText('other@acme.com')
    const selects = screen.getAllByRole('combobox')
    fireEvent.change(selects[1], { target: { value: 'ADMIN' } })
    await waitFor(() => expect(mockToastError).toHaveBeenCalled())
  })

  it('shows error alert when users query fails', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new Error('network error'))
    renderPage()
    expect(await screen.findByText(/failed to load users/i)).toBeInTheDocument()
  })

  it('disables a user and shows a "Disabled" badge', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    vi.mocked(apiMutate).mockResolvedValue({ ...USERS[1], disabled: true })
    renderPage()
    await screen.findByText('other@acme.com')
    fireEvent.click(screen.getAllByRole('button', { name: /^disable$/i })[1])
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/auth/admin/users/user-other/disable', undefined, 'POST'),
    )
    await waitFor(() => expect(mockToastSuccess).toHaveBeenCalledWith('User disabled'))
  })

  it('shows an Enable button and Disabled badge for a disabled user', async () => {
    const withDisabled: TenantUser[] = [USERS[0], { ...USERS[1], disabled: true }]
    vi.mocked(apiFetch).mockResolvedValue(withDisabled)
    renderPage()
    await screen.findByText('other@acme.com')
    expect(screen.getByText('Disabled')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^enable$/i })).toBeInTheDocument()
  })

  it('re-enables a disabled user', async () => {
    const withDisabled: TenantUser[] = [USERS[0], { ...USERS[1], disabled: true }]
    vi.mocked(apiFetch).mockResolvedValue(withDisabled)
    vi.mocked(apiMutate).mockResolvedValue({ ...USERS[1], disabled: false })
    renderPage()
    await screen.findByText('other@acme.com')
    fireEvent.click(screen.getByRole('button', { name: /^enable$/i }))
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/auth/admin/users/user-other/enable', undefined, 'POST'),
    )
    await waitFor(() => expect(mockToastSuccess).toHaveBeenCalledWith('User enabled'))
  })

  it('disables the Disable button for the current user', async () => {
    vi.mocked(apiFetch).mockResolvedValue(USERS)
    renderPage()
    await screen.findByText('other@acme.com')
    const disableButtons = screen.getAllByRole('button', { name: /^disable$/i })
    expect(disableButtons[0]).toBeDisabled()
    expect(disableButtons[1]).not.toBeDisabled()
  })
})
