import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'

const mockUser = vi.fn()
const mockIsHydrated = vi.fn()

vi.mock('#/stores/useAuthStore', () => ({
  useAuthStore: (selector: (s: { user: unknown; isHydrated: boolean }) => unknown) =>
    selector({ user: mockUser(), isHydrated: mockIsHydrated() }),
}))

vi.mock('#/components/AppLayout', () => ({
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  Link: ({ children }: { children: React.ReactNode }) => <a>{children}</a>,
  useRouterState: () => '/settings/connections',
}))

function renderLayout() {
  return render(
    <SettingsTabsLayout>
      <div>page content</div>
    </SettingsTabsLayout>,
  )
}

describe('SettingsTabsLayout — admin guard', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not show error before hydration even if user is null', () => {
    mockIsHydrated.mockReturnValue(false)
    mockUser.mockReturnValue(null)
    renderLayout()
    expect(screen.queryByText(/admin role/i)).not.toBeInTheDocument()
  })

  it('shows children (not error) before hydration when user is null', () => {
    mockIsHydrated.mockReturnValue(false)
    mockUser.mockReturnValue(null)
    renderLayout()
    expect(screen.getByText('page content')).toBeInTheDocument()
  })

  it('shows error after hydration when user has no admin role', () => {
    mockIsHydrated.mockReturnValue(true)
    mockUser.mockReturnValue({ roles: ['MEMBER'] })
    renderLayout()
    expect(screen.getByText(/admin role/i)).toBeInTheDocument()
  })

  it('does not show children when non-admin and hydrated', () => {
    mockIsHydrated.mockReturnValue(true)
    mockUser.mockReturnValue({ roles: ['MEMBER'] })
    renderLayout()
    expect(screen.queryByText('page content')).not.toBeInTheDocument()
  })

  it('shows children when user has ADMIN role (case-insensitive)', () => {
    mockIsHydrated.mockReturnValue(true)
    mockUser.mockReturnValue({ roles: ['admin'] })
    renderLayout()
    expect(screen.getByText('page content')).toBeInTheDocument()
    expect(screen.queryByText(/admin role/i)).not.toBeInTheDocument()
  })

  it('shows children when user has uppercase ADMIN role', () => {
    mockIsHydrated.mockReturnValue(true)
    mockUser.mockReturnValue({ roles: ['ADMIN'] })
    renderLayout()
    expect(screen.getByText('page content')).toBeInTheDocument()
  })

  it('shows tab navigation when admin', () => {
    mockIsHydrated.mockReturnValue(true)
    mockUser.mockReturnValue({ roles: ['ADMIN'] })
    renderLayout()
    expect(screen.getByText('Connections')).toBeInTheDocument()
    expect(screen.getByText('Notifications')).toBeInTheDocument()
    expect(screen.getByText('API Keys')).toBeInTheDocument()
    expect(screen.getByText('Tenant')).toBeInTheDocument()
  })
})
