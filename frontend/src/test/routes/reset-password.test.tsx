import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
  ApiError: class ApiError extends Error {
    code: string
    traceId: string
    constructor(code: string, message: string, traceId: string) {
      super(message)
      this.code = code
      this.traceId = traceId
    }
  },
}))

import { Route } from '#/routes/reset-password'
import { apiMutate, ApiError } from '#/lib/api'

function renderPage() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const Page = (Route as any).component
  return render(<Page />)
}

function fillForm(overrides: { token?: string; password?: string; confirm?: string } = {}) {
  const { token = '123456', password = 'newpass1', confirm = 'newpass1' } = overrides
  fireEvent.change(screen.getByLabelText(/reset code/i), { target: { value: token } })
  fireEvent.change(screen.getByLabelText(/^new password$/i), { target: { value: password } })
  fireEvent.change(screen.getByLabelText(/confirm password/i), { target: { value: confirm } })
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    searchParams.email = undefined
  })

  it('renders heading and all form fields', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /set a new password/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/reset code/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^new password$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/confirm password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /set new password/i })).toBeInTheDocument()
  })

  it('shows the email address from the search param in the description', () => {
    searchParams.email = 'user@example.com'
    renderPage()
    expect(screen.getByText(/user@example\.com/i)).toBeInTheDocument()
  })

  it('shows validation error when fields are empty', async () => {
    renderPage()
    fireEvent.submit(screen.getByRole('button', { name: /set new password/i }).closest('form')!)
    expect(await screen.findByText(/please fill in all fields/i)).toBeInTheDocument()
  })

  it('shows error when passwords do not match', async () => {
    renderPage()
    fillForm({ confirm: 'different' })
    fireEvent.submit(screen.getByRole('button', { name: /set new password/i }).closest('form')!)
    expect(await screen.findByText(/passwords do not match/i)).toBeInTheDocument()
  })

  it('navigates to /login on success', async () => {
    vi.mocked(apiMutate).mockResolvedValue(undefined)
    renderPage()
    fillForm()
    fireEvent.submit(screen.getByRole('button', { name: /set new password/i }).closest('form')!)
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/login' }))
  })

  it('shows API error message and traceId on failure', async () => {
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('TOKEN_EXPIRED', 'Reset code has expired', 'trace-rp'))
    renderPage()
    fillForm()
    fireEvent.submit(screen.getByRole('button', { name: /set new password/i }).closest('form')!)
    expect(await screen.findByText(/reset code has expired/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-rp/i)).toBeInTheDocument()
  })
})
