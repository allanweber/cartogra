import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/_authenticated/settings.profile'
import { apiMutate, ApiError } from '#/lib/api'

const mockNavigate = vi.fn()

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
  useNavigate: () => mockNavigate,
}))

vi.mock('#/components/AppLayout', () => ({
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

const mockUser = vi.fn()
const mockHydrateWith = vi.fn()

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: unknown; hydrateWith: unknown }) => unknown) =>
    selector({ user: mockUser(), hydrateWith: mockHydrateWith }),
}))

vi.mock('#/lib/api', () => ({
  ApiError: class extends Error {
    code: string
    traceId: string
    constructor(code: string, message: string, traceId: string) {
      super(message)
      this.code = code
      this.traceId = traceId
    }
  },
  apiMutate: vi.fn(),
}))

const mockApiMutate = vi.mocked(apiMutate)

function renderPage() {
  const Page = (Route as any).component
  return render(<Page />)
}

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders email badge and Admin badge for local-auth admin', () => {
    mockUser.mockReturnValue({ name: 'Alice Smith', email: 'alice@example.com', authProvider: 'local', roles: ['ADMIN'] })
    renderPage()
    expect(screen.getByDisplayValue('alice@example.com')).toBeInTheDocument()
    expect(screen.getByText('Admin')).toBeInTheDocument()
  })

  it('pre-fills name input from user store', () => {
    mockUser.mockReturnValue({ name: 'Alice Smith', email: 'alice@example.com', authProvider: 'local', roles: ['ADMIN'] })
    renderPage()
    expect(screen.getByRole('textbox', { name: /display name/i })).toHaveValue('Alice Smith')
  })

  it('pre-fills empty string when name is null', () => {
    mockUser.mockReturnValue({ name: null, email: 'bob@example.com', authProvider: 'local', roles: ['user'] })
    renderPage()
    expect(screen.getByRole('textbox', { name: /display name/i })).toHaveValue('')
  })

  it('shows User badge when roles contains only user', () => {
    mockUser.mockReturnValue({ name: 'Dave', email: 'dave@example.com', authProvider: 'local', roles: ['user'] })
    renderPage()
    expect(screen.getByText('User')).toBeInTheDocument()
  })

  it('shows Change password link for local-auth users', () => {
    mockUser.mockReturnValue({ name: 'Alice', email: 'alice@example.com', authProvider: 'local', roles: ['ADMIN'] })
    renderPage()
    const link = screen.getByRole('link', { name: /change password/i })
    expect(link).toBeInTheDocument()
    expect(link).toHaveAttribute('href', '/forgot-password')
  })

  it('hides Change password link for GitHub OAuth users', () => {
    mockUser.mockReturnValue({ name: 'Bob', email: 'bob@example.com', authProvider: 'github', roles: ['user'] })
    renderPage()
    expect(screen.queryByRole('link', { name: /change password/i })).not.toBeInTheDocument()
  })

  it('hides Change password link for Google OAuth users', () => {
    mockUser.mockReturnValue({ name: 'Carol', email: 'carol@example.com', authProvider: 'google', roles: ['user'] })
    renderPage()
    expect(screen.queryByRole('link', { name: /change password/i })).not.toBeInTheDocument()
  })

  it('saves name-only change without navigating', async () => {
    const user = { id: '1', name: 'Alice', email: 'alice@example.com', authProvider: 'local', tenantId: 't1', roles: ['ADMIN'] }
    mockUser.mockReturnValue(user)
    mockApiMutate.mockResolvedValueOnce({ ...user, name: 'New Name' })

    renderPage()
    fireEvent.change(screen.getByRole('textbox', { name: /display name/i }), { target: { value: 'New Name' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(mockApiMutate).toHaveBeenCalledWith('/auth/userinfo', { name: 'New Name', email: 'alice@example.com' }, 'PUT')
      expect(mockHydrateWith).toHaveBeenCalled()
    })
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('navigates to verify-email when email changes', async () => {
    const user = { id: '1', name: 'Alice', email: 'alice@example.com', authProvider: 'local', tenantId: 't1', roles: ['ADMIN'] }
    mockUser.mockReturnValue(user)
    const updatedUser = { ...user, email: 'newalice@example.com' }
    mockApiMutate.mockResolvedValueOnce(updatedUser)

    renderPage()
    fireEvent.change(screen.getByRole('textbox', { name: /email/i }), { target: { value: 'newalice@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(mockHydrateWith).toHaveBeenCalledWith(updatedUser)
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/verify-email',
        search: { email: 'newalice@example.com' },
      })
    })
  })

  it('shows error alert with traceId when save fails', async () => {
    mockUser.mockReturnValue({ name: 'Alice', email: 'alice@example.com', authProvider: 'local', roles: ['ADMIN'] })
    mockApiMutate.mockRejectedValueOnce(new ApiError('SERVER_ERROR', 'Something went wrong', 'abc123'))

    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
      expect(screen.getByText(/abc123/i)).toBeInTheDocument()
    })
    expect(mockHydrateWith).not.toHaveBeenCalled()
  })

  it('shows conflict error when email is already in use', async () => {
    mockUser.mockReturnValue({ name: 'Alice', email: 'alice@example.com', authProvider: 'local', roles: ['ADMIN'] })
    mockApiMutate.mockRejectedValueOnce(new ApiError('CONFLICT', 'Email is already in use.', 'trace999'))

    renderPage()
    fireEvent.change(screen.getByRole('textbox', { name: /email/i }), { target: { value: 'taken@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByText(/email is already in use/i)).toBeInTheDocument()
      expect(screen.getByText(/trace999/i)).toBeInTheDocument()
    })
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})
