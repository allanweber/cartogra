import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { TooltipProvider } from '#/components/ui/tooltip'
import { ApiError } from '#/lib/api'

import { RootErrorBoundary } from './__root'

const navigateMock = vi.fn()

vi.mock('@tanstack/react-router', async () => {
  const actual = await vi.importActual('@tanstack/react-router')

  return {
    ...actual,
    Link: ({ children, to, className }: { children: React.ReactNode; to: string; className?: string }) => (
      <a className={className} href={to}>
        {children}
      </a>
    ),
    useRouterState: ({ select }: { select: (state: { location: { pathname: string } }) => unknown }) =>
      select({ location: { pathname: '/dashboard' } }),
    useNavigate: () => navigateMock,
  }
})

function renderBoundary(error: Error, reset = vi.fn()) {
  const queryClient = new QueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RootErrorBoundary error={error} reset={reset} />
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return { reset }
}

describe('RootErrorBoundary', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the trace ID for an ApiError', () => {
    renderBoundary(new ApiError('INTERNAL', 'Something broke', 'a3f1c8d2b4e6f0912345678901234567'))

    expect(screen.getByText('Something broke')).toBeInTheDocument()
    expect(screen.getByText('Trace ID: a3f1c8d2b4e6f0912345678901234567')).toBeInTheDocument()
  })

  it('renders the no-trace-ID fallback for a plain Error', () => {
    renderBoundary(new Error('Unexpected failure'))

    expect(screen.getByText('Unexpected failure')).toBeInTheDocument()
    expect(screen.getByText('No trace ID was attached to this error.')).toBeInTheDocument()
  })

  it('calls reset() when Retry is clicked', () => {
    const { reset } = renderBoundary(new Error('Unexpected failure'))

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    expect(reset).toHaveBeenCalledTimes(1)
  })
})
