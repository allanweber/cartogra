import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/forgot-password'
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

describe('ForgotPasswordPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders heading and email field', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /reset your password/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/^email$/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /send reset code/i })).toBeInTheDocument()
  })

  it('shows validation error when email is empty', async () => {
    renderPage()
    fireEvent.submit(screen.getByRole('button', { name: /send reset code/i }).closest('form')!)
    expect(await screen.findByText(/please enter your email address/i)).toBeInTheDocument()
  })

  it('navigates to /reset-password with email on success', async () => {
    vi.mocked(apiMutate).mockResolvedValue(undefined)
    renderPage()
    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: 'user@example.com' } })
    fireEvent.submit(screen.getByRole('button', { name: /send reset code/i }).closest('form')!)
    await waitFor(() =>
      expect(navigateMock).toHaveBeenCalledWith({ to: '/reset-password', search: { email: 'user@example.com' } }),
    )
  })

  it('shows API error message and traceId on failure', async () => {
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('RATE_LIMITED', 'Too many requests', 'trace-fp'))
    renderPage()
    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: 'user@example.com' } })
    fireEvent.submit(screen.getByRole('button', { name: /send reset code/i }).closest('form')!)
    expect(await screen.findByText(/too many requests/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-fp/i)).toBeInTheDocument()
  })
})
