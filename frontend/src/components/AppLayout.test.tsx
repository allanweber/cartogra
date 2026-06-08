import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AppLayout } from '#/components/AppLayout'
import { TooltipProvider } from '#/components/ui/tooltip'

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
      select({ location: { pathname: '/catalog' } }),
    useNavigate: () => navigateMock,
  }
})

describe('AppLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders the shell header and navigation', () => {
    const queryClient = new QueryClient()
    render(
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <AppLayout
            title="Catalog"
            description="A fast registry for service ownership and change-risk decisions."
          >
            <div>Shell content</div>
          </AppLayout>
        </TooltipProvider>
      </QueryClientProvider>,
    )

    expect(screen.getByRole('heading', { name: 'Catalog' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Operations/i })).toBeInTheDocument()
    expect(screen.getByText('Shell content')).toBeInTheDocument()
  })
})