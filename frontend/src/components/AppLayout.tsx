import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import {
  Activity,
  Brain,
  Building2,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Compass,
  FileText,
  FolderKanban,
  GitBranch,
  LayoutDashboard,
  LogOut,
  Menu,
  UserRound,
  Moon,
  Search,
  Settings,
  ShieldCheck,
  Sun,
  TriangleAlert,
  Users,
} from 'lucide-react'
import { useEffect, useState } from 'react'

import { Button } from '#/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '#/components/ui/dropdown-menu'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from '#/components/ui/sheet'
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '#/components/ui/tooltip'
import { CommandPalette } from '#/components/CommandPalette'
import { NotificationBell } from '#/components/NotificationBell'
import { apiMutate } from '#/lib/api'
import { clearSessionCookie } from '#/lib/session'
import { cn } from '#/lib/utils'
import { useAuthStore } from '#/stores/useAuthStore'
import { useThemeStore } from '#/stores/useThemeStore'
import { TENANTS, useCurrentTenant, useTenantStore } from '#/stores/useTenantStore'

import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'

interface AppLayoutProps {
  title: string
  description?: string
  eyebrow?: string
  actions?: ReactNode
  children: ReactNode
}

type NavRoute =
  | '/dashboard'
  | '/catalog'
  | '/graph'
  | '/risks'
  | '/contracts'
  | '/teams'
  | '/timeline'
  | '/intelligence'
  | '/ops'
  | '/settings'

interface NavigationItem {
  label: string
  to: NavRoute
  icon: LucideIcon
}

const navigationItems: NavigationItem[] = [
  { label: 'Dashboard', to: '/dashboard', icon: LayoutDashboard },
  { label: 'Catalog', to: '/catalog', icon: FolderKanban },
  { label: 'Graph', to: '/graph', icon: GitBranch },
  { label: 'Risks', to: '/risks', icon: TriangleAlert },
  { label: 'Contracts', to: '/contracts', icon: ShieldCheck },
  { label: 'Teams', to: '/teams', icon: Users },
  { label: 'Timeline', to: '/timeline', icon: Activity },
  { label: 'Intelligence', to: '/intelligence', icon: Brain },
  { label: 'Operations', to: '/ops', icon: FileText },
  { label: 'Settings', to: '/settings', icon: Settings },
]

export function AppLayout({
  title,
  description,
  eyebrow,
  actions,
  children,
}: AppLayoutProps) {
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  })
  const [collapsed, setCollapsed] = useState(false)
  const [cmdOpen, setCmdOpen] = useState(false)

  // Cmd/Ctrl+K to open command palette
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        setCmdOpen((v) => !v)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [])

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {/* Skip to main content */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-background focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:ring-2 focus:ring-ring focus:outline-none"
      >
        Skip to content
      </a>

      {/* Desktop sidebar */}
      <aside
        className={cn(
          'sticky top-0 hidden h-screen shrink-0 flex-col border-r border-border bg-sidebar transition-all duration-200 md:flex',
          collapsed ? 'w-14' : 'w-56',
        )}
      >
        <SidebarContent
          pathname={pathname}
          collapsed={collapsed}
          onToggleCollapse={() => setCollapsed((c) => !c)}
        />
      </aside>

      {/* Main content area */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-10 flex h-14 shrink-0 items-center gap-2 border-b border-border bg-background/90 px-4 backdrop-blur sm:px-6">
          {/* Mobile nav trigger */}
          <div className="md:hidden">
            <NavigationSheet pathname={pathname} />
          </div>

          <div className="min-w-0 flex-1">
            {eyebrow && (
              <p className="mb-0.5 text-xs font-medium tracking-widest text-muted-foreground uppercase">
                {eyebrow}
              </p>
            )}
            <h1 className="truncate text-xl font-semibold tracking-tight leading-none">{title}</h1>
            {description && (
              <p className="mt-0.5 truncate text-xs text-muted-foreground">{description}</p>
            )}
          </div>

          {/* Right side: actions + search + notifications */}
          <div className="flex shrink-0 items-center gap-1.5">
            {actions && (
              <div className="mr-1 flex items-center gap-2">{actions}</div>
            )}

            {/* Command search button */}
            <Tooltip>
              <TooltipTrigger asChild>
                <button
                  onClick={() => setCmdOpen(true)}
                  className="flex h-9 w-48 items-center gap-2 rounded-lg border border-border bg-muted/50 px-3 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:w-64 md:w-80 lg:w-96"
                  aria-label="Search"
                >
                  <Search className="size-3.5 shrink-0" />
                  <span className="hidden flex-1 text-left text-xs sm:block">Search…</span>
                  <kbd className="hidden shrink-0 rounded border border-border bg-background px-1 py-0.5 text-xs sm:block">
                    ⌘K
                  </kbd>
                </button>
              </TooltipTrigger>
              <TooltipContent>Command palette (⌘K)</TooltipContent>
            </Tooltip>

            {/* Notifications */}
            <NotificationBell />
          </div>
        </header>

        <main id="main-content" className="flex-1 p-4 sm:p-6">
          {children}
        </main>
      </div>

      <CommandPalette open={cmdOpen} onOpenChange={setCmdOpen} />
    </div>
  )
}

function TenantSelect({ collapsed }: { collapsed: boolean }) {
  const currentTenant = useCurrentTenant()
  const { setTenant } = useTenantStore()
  const queryClient = useQueryClient()

  if (collapsed) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            className="flex size-8 items-center justify-center rounded-lg text-muted-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground pointer-coarse:size-11"
            aria-label="Switch tenant"
          >
            <Building2 className="size-4" />
          </button>
        </TooltipTrigger>
        <TooltipContent side="right">{currentTenant.name}</TooltipContent>
      </Tooltip>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-sidebar-accent">
          <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Building2 className="size-4" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">{currentTenant.name}</p>
            <p className="truncate text-xs text-muted-foreground">{currentTenant.plan}</p>
          </div>
          <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" className="w-52">
        <DropdownMenuLabel className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
          Workspaces
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {TENANTS.map((t) => (
          <DropdownMenuItem
            key={t.id}
            onClick={() => {
              setTenant(t.id)
              queryClient.clear()
            }}
            className="flex items-center gap-2"
          >
            <div className="flex size-6 items-center justify-center rounded bg-muted text-xs font-bold">
              {t.name.slice(0, 2).toUpperCase()}
            </div>
            <div className="flex-1">
              <p className="text-sm">{t.name}</p>
            </div>
            {t.id === currentTenant.id && (
              <Check className="size-3.5 text-primary" />
            )}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem className="text-xs text-muted-foreground">
          + Add workspace
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function SidebarContent({
  pathname,
  collapsed,
  onToggleCollapse,
}: {
  pathname: string
  collapsed: boolean
  onToggleCollapse: () => void
}) {
  const { theme, toggleTheme } = useThemeStore()

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Logo row */}
      <div className={cn('flex h-14 shrink-0 items-center border-b border-border', collapsed ? 'justify-center gap-1.5 px-2' : 'gap-2.5 px-4')}>
        <Compass className="size-4 shrink-0 text-primary" aria-hidden="true" />
        {!collapsed && (
          <span className="flex-1 text-sm font-semibold tracking-tight">Cartogra</span>
        )}
        <button
          onClick={onToggleCollapse}
          className="flex size-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground pointer-coarse:size-11"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? (
            <ChevronRight className="size-4" />
          ) : (
            <ChevronLeft className="size-4" />
          )}
        </button>
      </div>

      {/* Nav items */}
      <nav aria-label="Main navigation" className="flex-1 overflow-y-auto py-3">
        <ul className={cn('space-y-0.5', collapsed ? 'px-1.5' : 'px-2')}>
          {navigationItems.map((item) => {
            const Icon = item.icon
            const isActive =
              pathname === item.to || pathname.startsWith(`${item.to}/`)

            const linkEl = (
              <Link
                to={item.to}
                aria-current={isActive ? 'page' : undefined}
                className={cn(
                  'flex items-center gap-3 rounded-lg px-2.5 py-2 text-sm font-medium transition-colors',
                  collapsed && 'justify-center px-0',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'text-muted-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                )}
              >
                <Icon className="size-4 shrink-0" aria-hidden="true" />
                {!collapsed && <span>{item.label}</span>}
              </Link>
            )

            if (collapsed) {
              return (
                <li key={item.to}>
                  <Tooltip>
                    <TooltipTrigger asChild>{linkEl}</TooltipTrigger>
                    <TooltipContent side="right">{item.label}</TooltipContent>
                  </Tooltip>
                </li>
              )
            }

            return <li key={item.to}>{linkEl}</li>
          })}
        </ul>
      </nav>

      {/* Bottom controls */}
      <div className={cn('border-t border-border py-3', collapsed ? 'px-1.5 space-y-1' : 'px-3 space-y-1')}>
        {/* Tenant select */}
        <TenantSelect collapsed={collapsed} />

        {/* Theme toggle */}
        <Tooltip>
          <TooltipTrigger asChild>
            <button
              onClick={toggleTheme}
              className={cn(
                'flex w-full items-center gap-3 rounded-lg px-2.5 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                collapsed && 'justify-center px-0',
              )}
              aria-label="Toggle theme"
            >
              {theme === 'dark' ? (
                <Sun className="size-4 shrink-0" aria-hidden="true" />
              ) : (
                <Moon className="size-4 shrink-0" aria-hidden="true" />
              )}
              {!collapsed && (
                <span>{theme === 'dark' ? 'Light mode' : 'Dark mode'}</span>
              )}
            </button>
          </TooltipTrigger>
          {collapsed && (
            <TooltipContent side="right">
              {theme === 'dark' ? 'Light mode' : 'Dark mode'}
            </TooltipContent>
          )}
        </Tooltip>

        {/* User menu */}
        <UserMenu collapsed={collapsed} />
      </div>
    </div>
  )
}

function UserMenu({ collapsed }: { collapsed: boolean }) {
  const user = useAuthStore((s) => s.user)
  const clearAuth = useAuthStore((s) => s.clearAuth)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const initials = user?.email
    ? user.email.slice(0, 2).toUpperCase()
    : '??'

  async function handleLogout() {
    try {
      await apiMutate('/auth/logout', {})
    } catch {
      // proceed even if the server call fails
    }
    queryClient.removeQueries({ queryKey: ['session'] })
    clearSessionCookie()
    clearAuth()
    navigate({ to: '/login' })
  }

  const avatar = (
    <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground">
      {initials}
    </div>
  )

  if (collapsed) {
    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            className="flex w-full justify-center rounded-lg py-1 hover:bg-sidebar-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label="User menu"
          >
            {avatar}
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent side="right" align="end" className="w-44">
          <DropdownMenuLabel className="truncate text-xs text-muted-foreground">{user?.name ?? user?.email }</DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem asChild>
            <Link to="/settings/profile" className="flex cursor-pointer items-center gap-2">
              <UserRound className="size-4" />
              Profile
            </Link>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={handleLogout} className="gap-2 text-destructive focus:text-destructive">
            <LogOut className="size-4" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-sidebar-accent">
          {avatar}
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">{user?.name ?? user?.email ?? '—'}</p>
            <p className="truncate text-xs text-muted-foreground capitalize">{user?.roles[0] ?? 'user'}</p>
          </div>
          <ChevronDown className="size-3.5 shrink-0 text-muted-foreground" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" align="start" className="w-52">
        <DropdownMenuLabel className="truncate text-xs text-muted-foreground">{user?.name ?? user?.email}</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link to="/settings/profile" className="flex cursor-pointer items-center gap-2">
            <UserRound className="size-4" />
            Profile
          </Link>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={handleLogout} className="gap-2 text-destructive focus:text-destructive">
          <LogOut className="size-4" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function NavigationSheet({ pathname }: { pathname: string }) {
  return (
    <Sheet>
      <SheetTrigger asChild>
        <Button variant="outline" size="icon" aria-label="Open navigation">
          <Menu className="size-4" />
        </Button>
      </SheetTrigger>
      <SheetContent side="left" className="w-56 border-sidebar-border bg-sidebar p-0">
        <SheetHeader className="sr-only">
          <SheetTitle>Navigation</SheetTitle>
          <SheetDescription>Main application navigation</SheetDescription>
        </SheetHeader>
        <SidebarContent pathname={pathname} collapsed={false} onToggleCollapse={() => {}} />
      </SheetContent>
    </Sheet>
  )
}
