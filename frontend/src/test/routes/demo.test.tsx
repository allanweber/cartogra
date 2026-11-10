import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { Route } from '#/routes/demo'
import { ApiError, startGuestSession } from '#/lib/api'

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
  startGuestSession: vi.fn(),
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

describe('DemoPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('starts a guest session and lands on the dashboard', async () => {
    vi.mocked(startGuestSession).mockResolvedValue({ expiresIn: 3600 })
    renderPage()
    expect(screen.getByText(/opening the demo/i)).toBeInTheDocument()
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith({ to: '/dashboard' }))
  })

  it('explains a rate-limited mint with the trace id', async () => {
    vi.mocked(startGuestSession).mockRejectedValue(new ApiError('RATE_LIMITED', 'Too many requests', 'trace-9'))
    renderPage()
    expect(await screen.findByText(/too many demo sessions/i)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/too many demo sessions/i)
    expect(screen.getByText(/trace-9/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to sign in/i })).toHaveAttribute('href', '/login')
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('reports a disabled demo', async () => {
    vi.mocked(startGuestSession).mockRejectedValue(new ApiError('NOT_FOUND', 'Guest demo is not enabled', 't'))
    renderPage()
    expect(await screen.findByText(/not available on this instance/i)).toBeInTheDocument()
  })
})
