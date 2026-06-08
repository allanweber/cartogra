import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const navigateMock = vi.fn()
const searchParams: { redirect?: string; error?: string } = {}

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

vi.mock('#/lib/session', () => ({ writeSessionCookie: vi.fn() }))

// Valid base64-encoded JWT payload for the success path
const JWT_PAYLOAD = btoa(JSON.stringify({ sub: 'u1', tid: 't1', email: 'a@b.com', roles: ['user'] }))
const MOCK_TOKEN = `header.${JWT_PAYLOAD}.sig`

import { Route } from '#/routes/login'
import { apiMutate, ApiError } from '#/lib/api'

function renderPage() {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const Page = (Route as any).component
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    searchParams.redirect = undefined
    searchParams.error = undefined
    sessionStorage.clear()
  })

  it('renders heading, email/password fields, and submit button', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: /sign in to your workspace/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/^email$/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^sign in$/i })).toBeInTheDocument()
  })

  it('renders GitHub and Google OAuth buttons', () => {
    renderPage()
    expect(screen.getByRole('button', { name: /continue with github/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /continue with google/i })).toBeInTheDocument()
  })

  it('shows validation error when fields are empty', async () => {
    renderPage()
    fireEvent.submit(screen.getByRole('button', { name: /^sign in$/i }).closest('form')!)
    expect(await screen.findByText(/please fill in all fields/i)).toBeInTheDocument()
  })

  it('shows API error message and traceId on failed login', async () => {
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('INVALID_CREDENTIALS', 'Invalid email or password', 'trace-abc'))
    renderPage()
    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: 'a@b.com' } })
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: 'secret123' } })
    fireEvent.submit(screen.getByRole('button', { name: /^sign in$/i }).closest('form')!)
    expect(await screen.findByText(/invalid email or password/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-abc/i)).toBeInTheDocument()
  })

  it('navigates to the redirect param on successful login', async () => {
    searchParams.redirect = '/catalog'
    vi.mocked(apiMutate).mockResolvedValue({ accessToken: MOCK_TOKEN, expiresIn: 3600, refreshToken: 'rt' })
    renderPage()
    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: 'a@b.com' } })
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: 'secret123' } })
    fireEvent.submit(screen.getByRole('button', { name: /^sign in$/i }).closest('form')!)
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/catalog' }))
  })

  it('navigates to /dashboard when no redirect param', async () => {
    vi.mocked(apiMutate).mockResolvedValue({ accessToken: MOCK_TOKEN, expiresIn: 3600, refreshToken: 'rt' })
    renderPage()
    fireEvent.change(screen.getByLabelText(/^email$/i), { target: { value: 'a@b.com' } })
    fireEvent.change(screen.getByLabelText(/^password$/i), { target: { value: 'secret123' } })
    fireEvent.submit(screen.getByRole('button', { name: /^sign in$/i }).closest('form')!)
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/dashboard' }))
  })

  it('shows OAuth error banner when ?error=oauth_failed', () => {
    searchParams.error = 'oauth_failed'
    renderPage()
    expect(screen.getByText(/sign-in with oauth failed/i)).toBeInTheDocument()
  })
})
