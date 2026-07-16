import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, fireEvent } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { AppLayout } from '#/components/AppLayout'
import { TooltipProvider } from '#/components/ui/tooltip'

const navigateMock = vi.fn()

// The real store persists to window.localStorage, which this test environment
// does not provide. Re-implement the same shape without the persist middleware
// so tenant switching can be exercised without an environment-only crash.
vi.mock('#/stores/useTenantStore', async () => {
  const { create } = await import('zustand')

  const TENANTS = [
    { id: 'ten-1', name: 'Acme Fintech', plan: 'Alpha' },
    { id: 'ten-2', name: 'Beta Corp', plan: 'Alpha' },
    { id: 'ten-3', name: 'Gamma Ltd', plan: 'Alpha' },
  ]

  const useTenantStore = create((set: (partial: { currentTenantId: string }) => void) => ({
    currentTenantId: 'ten-1',
    setTenant: (id: string) => set({ currentTenantId: id }),
  }))

  function useCurrentTenant() {
    const id = useTenantStore((s: { currentTenantId: string }) => s.currentTenantId)
    return TENANTS.find((t) => t.id === id) ?? TENANTS[0]
  }

  return { TENANTS, useTenantStore, useCurrentTenant }
})

const { useTenantStore } = await import('#/stores/useTenantStore')

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

vi.mock('#/components/ui/dropdown-menu', async () => {
  const { createContext, useContext, useState } = await import('react')

  type Ctx = { open: boolean; toggle: () => void }
  const Ctx = createContext<Ctx>({ open: false, toggle: () => {} })

  return {
    DropdownMenu: ({ children }: { children: React.ReactNode }) => {
      const [open, setOpen] = useState(false)
      return <Ctx.Provider value={{ open, toggle: () => setOpen((o) => !o) }}><div>{children}</div></Ctx.Provider>
    },
    DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => {
      const { toggle } = useContext(Ctx)
      return <div onClick={toggle}>{children}</div>
    },
    DropdownMenuContent: ({ children }: { children: React.ReactNode }) => {
      const { open } = useContext(Ctx)
      return open ? <div>{children}</div> : null
    },
    DropdownMenuItem: ({ children, onClick, className }: { children: React.ReactNode; onClick?: () => void; className?: string }) => (
      <button onClick={onClick} className={className}>{children}</button>
    ),
    DropdownMenuLabel: ({ children, className }: { children: React.ReactNode; className?: string }) => (
      <div className={className}>{children}</div>
    ),
    DropdownMenuSeparator: () => <hr />,
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

  it('clears the React Query cache and switches tenants when a workspace is picked', () => {
    useTenantStore.setState({ currentTenantId: 'ten-1' })

    const queryClient = new QueryClient()
    queryClient.setQueryData(['services'], [{ id: 'svc-1', name: 'payments-api' }])
    expect(queryClient.getQueryData(['services'])).toBeDefined()

    render(
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <AppLayout title="Catalog" description="desc">
            <div>Shell content</div>
          </AppLayout>
        </TooltipProvider>
      </QueryClientProvider>,
    )

    fireEvent.click(screen.getByText('Acme Fintech'))
    fireEvent.click(screen.getByText('Beta Corp'))

    expect(queryClient.getQueryData(['services'])).toBeUndefined()
    expect(useTenantStore.getState().currentTenantId).toBe('ten-2')
  })
})