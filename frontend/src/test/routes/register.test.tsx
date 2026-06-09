import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/register'
import { apiMutate, ApiError } from '#/lib/api'

const navigateMock = vi.fn()

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => ({ ...opts }),
  useNavigate: () => navigateMock,
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
}))

vi.mock('#/lib/api', () => ({
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

function renderPage() {
  const Page = (Route as any).component
  return render(<Page />)
}

function fillForm(overrides: { name?: string; email?: string; password?: string; confirm?: string } = {}) {
  const { name = 'Acme Corp', email = 'user@example.com', password = 'secret123', confirm = 'secret123' } = overrides
  fireEvent.change(screen.getByLabelText(/organization name/i), { target: { value: name } })
  fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: email } })
  fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: password } })
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: confirm } })
}

describe('RegisterPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders heading and all form fields', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /create your workspace/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/organization name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^email$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/confirm password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument()
  })

  it('shows validation error when fields are empty', async () => {
    renderPage()
    fireEvent.submit(screen.getByRole('button', { name: /create account/i }).closest('form')!)
    expect(await screen.findByText(/please fill in all fields/i)).toBeInTheDocument()
  })

  it('shows error when passwords do not match', async () => {
    renderPage()
    fillForm({ confirm: 'different' })
    fireEvent.submit(screen.getByRole('button', { name: /create account/i }).closest('form')!)
    expect(await screen.findByText(/passwords do not match/i)).toBeInTheDocument()
  })

  it('shows error when password is shorter than 8 characters', async () => {
    renderPage()
    fillForm({ password: 'short', confirm: 'short' })
    fireEvent.submit(screen.getByRole('button', { name: /create account/i }).closest('form')!)
    expect(await screen.findByText(/password must be at least 8 characters/i)).toBeInTheDocument()
  })

  it('navigates to /verify-email with email on success', async () => {
    vi.mocked(apiMutate).mockResolvedValue(undefined)
    renderPage()
    fillForm()
    fireEvent.submit(screen.getByRole('button', { name: /create account/i }).closest('form')!)
    await waitFor(() =>
      expect(navigateMock).toHaveBeenCalledWith({ to: '/verify-email', search: { email: 'user@example.com' } }),
    )
  })

  it('shows API error message and traceId on failure', async () => {
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('EMAIL_IN_USE', 'Email already registered', 'trace-reg'))
    renderPage()
    fillForm()
    fireEvent.submit(screen.getByRole('button', { name: /create account/i }).closest('form')!)
    expect(await screen.findByText(/email already registered/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-reg/i)).toBeInTheDocument()
  })
})
