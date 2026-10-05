import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { buildNotifications, NotificationBell } from '#/components/NotificationBell'
import { dismissedStorageKey } from '#/hooks/useRiskDismissals'
import { apiFetch } from '#/lib/api'
import { useAuthStore } from '#/stores/useAuthStore'

import type { Risk, RisksPage } from '#/lib/topology-types'

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  Link: ({ children, to }: { children: React.ReactNode; to: string }) => <a href={to}>{children}</a>,
  useNavigate: () => vi.fn(),
}))

vi.mock('#/lib/api', () => ({ apiFetch: vi.fn() }))

function risk(overrides: Partial<Risk> = {}): Risk {
  return {
    id: 'cycle:a,b',
    type: 'cycle',
    severity: 'critical',
    title: 'Circular dependency among 2 services',
    explanation: '',
    fix: '',
    affectedServices: ['s1', 's2'],
    ...overrides,
  }
}

describe('buildNotifications', () => {
  const names = new Map([
    ['s1', 'payments'],
    ['s2', 'checkout'],
  ])

  it('drops info-severity risks', () => {
    expect(buildNotifications([risk({ severity: 'info' })], names)).toEqual([])
  })

  it('maps a non-orphan risk to a notification targeting its first affected service', () => {
    const [n] = buildNotifications([risk()], names)

    expect(n).toMatchObject({ id: 'risk-cycle:a,b', serviceId: 's1', severity: 'critical', subtitle: 'payments, checkout' })
  })

  it('groups orphans into one notification whose id depends on the sorted orphan ids', () => {
    const orphans = [
      risk({ id: 'orphan:b', type: 'orphan', severity: 'warning', affectedServices: ['s2'] }),
      risk({ id: 'orphan:a', type: 'orphan', severity: 'warning', affectedServices: ['s1'] }),
    ]

    const [grouped] = buildNotifications(orphans, names)

    expect(grouped).toMatchObject({ id: 'risk-orphans-orphan:a,orphan:b', serviceId: null, title: '2 unowned services' })
  })

  it('uses the singular for one orphan', () => {
    const [grouped] = buildNotifications([risk({ id: 'orphan:a', type: 'orphan', severity: 'warning' })], names)

    expect(grouped.title).toBe('1 unowned service')
  })
})

describe('NotificationBell', () => {
  const user = { id: 'u1', email: 'a@b.c', name: null, authProvider: 'local', tenantId: 't1', roles: [] }

  function mockRisks(items: Risk[]) {
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path.includes('/v1/topology/risks')) {
        const page: RisksPage = { items, total: items.length, limit: 200, offset: 0, truncated: false }
        return Promise.resolve(page)
      }
      return Promise.resolve({ items: [], total: 0, limit: 1000, offset: 0 })
    })
  }

  function renderBell() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={client}>
        <NotificationBell />
      </QueryClientProvider>,
    )
  }

  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({ user, isAuthenticated: true })
  })

  afterEach(() => {
    vi.resetAllMocks()
    useAuthStore.setState({ user: null, isAuthenticated: false })
  })

  it('does not count a risk dismissed on the Risks page', async () => {
    localStorage.setItem(dismissedStorageKey('t1', 'u1'), JSON.stringify(['cycle:a,b']))
    mockRisks([risk()])

    renderBell()

    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    expect(screen.queryByText('1')).not.toBeInTheDocument()
  })

  it('scopes read state to the signed-in tenant and user', async () => {
    mockRisks([risk()])
    renderBell()
    fireEvent.keyDown(await screen.findByRole('button', { name: /notifications/i }), { key: 'Enter' })
    fireEvent.click(await screen.findByText(/mark all read/i))

    expect(localStorage.getItem('cartogra:bell:read:t1:u1')).toContain('risk-cycle:a,b')
    expect(localStorage.getItem('cartogra:bell:read')).toBeNull()
  })
})
