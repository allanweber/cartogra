import { Link, useRouterState } from '@tanstack/react-router'
import type { ReactNode } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { cn } from '#/lib/utils'
import { useAuthStore } from '#/stores/useAuthStore'

const TABS = [
  { label: 'Connections', to: '/settings/connections' },
  { label: 'Notifications', to: '/settings/notifications' },
  { label: 'API Keys', to: '/settings/api-keys' },
  { label: 'Users', to: '/settings/users' },
  { label: 'Tenant', to: '/settings/tenant' },
] as const

interface Props {
  children: ReactNode
}

export function SettingsTabsLayout({ children }: Props) {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  const user = useAuthStore((s) => s.user)
  const isHydrated = useAuthStore((s) => s.isHydrated)
  const isAdmin = user?.roles.some((r) => r.toLowerCase() === 'admin') ?? false

  return (
    <AppLayout title="Settings" description="Manage integrations, notifications, and tenant configuration">
      {isHydrated && !isAdmin ? (
        <Alert variant="destructive" className="max-w-2xl">
          <AlertDescription>You need the Admin role to access settings.</AlertDescription>
        </Alert>
      ) : (
        <div className="space-y-6">
          <nav className="border-b border-border">
            <ul className="flex gap-1">
              {TABS.map((tab) => {
                const active = pathname === tab.to || pathname.startsWith(tab.to)
                return (
                  <li key={tab.to}>
                    <Link
                      to={tab.to}
                      className={cn(
                        'inline-block border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
                        active
                          ? 'border-primary text-primary'
                          : 'border-transparent text-muted-foreground hover:text-foreground',
                      )}
                    >
                      {tab.label}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </nav>
          {children}
        </div>
      )}
    </AppLayout>
  )
}
