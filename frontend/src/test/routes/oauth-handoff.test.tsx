import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/oauth-handoff'
import { apiFetch, ApiError } from '#/lib/api'

const navigateMock = vi.fn()
const oauthParams: { code?: string; state?: string; error?: string } = {}

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => ({ ...opts }),
  useNavigate: () => navigateMock,
  useSearch: () => ({ ...oauthParams }),
  Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
    <a href={to} className={className}>{children}</a>
  ),
}))

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

function renderPage() {
  const Page = (Route as any).component
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('OAuthHandoffPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    oauthParams.code = undefined
    oauthParams.state = undefined
    oauthParams.error = undefined
    sessionStorage.clear()
  })

  it('shows spinner while exchanging code', () => {
    oauthParams.code = 'auth-code-123'
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {})) // never resolves
    renderPage()
    expect(screen.getByText(/completing sign-in/i)).toBeInTheDocument()
  })

  it('shows error immediately when OAuth provider returns an error param', () => {
    oauthParams.error = 'access_denied'
    renderPage()
    expect(screen.getByText('access_denied')).toBeInTheDocument()
  })

  it('shows error when no code and no error are present', () => {
    renderPage()
    expect(screen.getByText(/did not return an authorization code/i)).toBeInTheDocument()
  })

  it('navigates to pendingRedirect and clears it on successful code exchange', async () => {
    oauthParams.code = 'auth-code-123'
    sessionStorage.setItem('pendingRedirect', '/catalog/abc')
    vi.mocked(apiFetch).mockResolvedValue(undefined)
    renderPage()
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/catalog/abc' }))
    expect(sessionStorage.getItem('pendingRedirect')).toBeNull()
  })

  it('navigates to /dashboard when pendingRedirect is absent', async () => {
    oauthParams.code = 'auth-code-123'
    vi.mocked(apiFetch).mockResolvedValue(undefined)
    renderPage()
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/dashboard' }))
  })

  it('shows API error and traceId when code exchange fails', async () => {
    oauthParams.code = 'bad-code'
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('OAUTH_FAILED', 'OAuth authentication failed', 'trace-oh'))
    renderPage()
    expect(await screen.findByText(/oauth authentication failed/i)).toBeInTheDocument()
    expect(await screen.findByText(/trace-oh/i)).toBeInTheDocument()
  })
})
