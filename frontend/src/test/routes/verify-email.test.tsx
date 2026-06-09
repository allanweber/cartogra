import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/verify-email'
import { apiMutate, ApiError } from '#/lib/api'

const navigateMock = vi.fn()
const searchParams: { email?: string } = {}

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => ({ ...opts, useSearch: () => ({ ...searchParams }) }),
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

describe('VerifyEmailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    searchParams.email = undefined
  })

  it('renders heading and verification code field', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /verify your email/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/verification code/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^verify email$/i })).toBeInTheDocument()
  })

  it('shows the email address from the search param', () => {
    searchParams.email = 'user@example.com'
    renderPage()
    expect(screen.getByText(/user@example\.com/i)).toBeInTheDocument()
  })

  it('shows error when email param is missing on submit', async () => {
    renderPage()
    fireEvent.change(screen.getByLabelText(/verification code/i), { target: { value: '123456' } })
    fireEvent.submit(screen.getByRole('button', { name: /^verify email$/i }).closest('form')!)
    expect(await screen.findByText(/email is missing/i)).toBeInTheDocument()
  })

  it('shows error when token is not 6 digits', async () => {
    searchParams.email = 'user@example.com'
    renderPage()
    fireEvent.change(screen.getByLabelText(/verification code/i), { target: { value: '123' } })
    fireEvent.submit(screen.getByRole('button', { name: /^verify email$/i }).closest('form')!)
    expect(await screen.findByText(/please enter the 6-digit code/i)).toBeInTheDocument()
  })

  it('navigates to /login on successful verification', async () => {
    searchParams.email = 'user@example.com'
    vi.mocked(apiMutate).mockResolvedValue(undefined)
    renderPage()
    fireEvent.change(screen.getByLabelText(/verification code/i), { target: { value: '123456' } })
    fireEvent.submit(screen.getByRole('button', { name: /^verify email$/i }).closest('form')!)
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/login' }))
  })

  it('shows confirmation message after resend succeeds', async () => {
    searchParams.email = 'user@example.com'
    vi.mocked(apiMutate).mockResolvedValue(undefined)
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /resend code/i }))
    expect(await screen.findByText(/code resent successfully/i)).toBeInTheDocument()
  })

  it('shows API error and traceId on verification failure', async () => {
    searchParams.email = 'user@example.com'
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('INVALID_TOKEN', 'Invalid or expired code', 'trace-ve'))
    renderPage()
    fireEvent.change(screen.getByLabelText(/verification code/i), { target: { value: '123456' } })
    fireEvent.submit(screen.getByRole('button', { name: /^verify email$/i }).closest('form')!)
    expect(await screen.findByText(/invalid or expired code/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-ve/i)).toBeInTheDocument()
  })
})
