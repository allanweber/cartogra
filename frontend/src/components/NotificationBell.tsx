import { Bell, BellDot, CheckCheck, ExternalLink } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'

import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '#/components/ui/dropdown-menu'
import { ScrollArea } from '#/components/ui/scroll-area'
import { formatAffected, useRisks, useServiceNames } from '#/hooks/useRisks'
import { cn } from '#/lib/utils'

import type { Risk } from '#/lib/topology-types'

interface Notification {
  id: string
  serviceId: string | null
  title: string
  subtitle: string
  severity: 'critical' | 'warning' | 'info' | 'success'
  read: boolean
}

function buildNotifications(
  riskItems: Risk[],
  serviceNames: Map<string, string>,
): Notification[] {
  const actionable = riskItems.filter((r) => r.severity !== 'info')
  const orphans = actionable.filter((r) => r.type === 'orphan')
  const others: Notification[] = actionable
    .filter((r) => r.type !== 'orphan')
    .map((r) => ({
      id: `risk-${r.id}`,
      serviceId: r.affectedServices[0] ?? null,
      title: r.title,
      subtitle: formatAffected(r.affectedServices, serviceNames),
      severity: r.severity === 'critical' ? 'critical' : 'warning',
      read: false,
    }))

  if (orphans.length === 0) return others

  const orphanIds = orphans.map((r) => r.id).sort()
  const grouped: Notification = {
    id: `risk-orphans-${orphanIds.join(',')}`,
    serviceId: null,
    title: `${orphans.length} unowned ${orphans.length === 1 ? 'service' : 'services'}`,
    subtitle: 'Assign a team in the catalog',
    severity: 'warning',
    read: false,
  }
  return [...others, grouped]
}

const READ_KEY = 'cartogra:bell:read'

const severityConfig: Record<Notification['severity'], { dot: string; text: string }> = {
  critical: { dot: 'bg-critical', text: 'text-critical' },
  warning: { dot: 'bg-warning', text: 'text-warning' },
  info: { dot: 'bg-info', text: 'text-info' },
  success: { dot: 'bg-success', text: 'text-success' },
}

export function NotificationBell() {
  const navigate = useNavigate()
  const { data: risksPage } = useRisks({ staleTime: 60_000 })
  const serviceNames = useServiceNames()
  const [readIds, setReadIds] = useState<Set<string>>(new Set())

  useEffect(() => {
    try {
      const raw = localStorage.getItem(READ_KEY)
      if (raw) setReadIds(new Set(JSON.parse(raw) as string[]))
    } catch {
      // per-viewer convenience only
    }
  }, [])

  function persistRead(next: Set<string>) {
    setReadIds(next)
    try {
      localStorage.setItem(READ_KEY, JSON.stringify([...next]))
    } catch {
      // per-viewer convenience only
    }
  }
  const notifications = buildNotifications(risksPage?.items ?? [], serviceNames).map((n) => ({
    ...n,
    read: readIds.has(n.id),
  }))
  const unreadCount = notifications.filter((n) => !n.read).length

  function markAllRead() {
    persistRead(new Set([...readIds, ...notifications.map((n) => n.id)]))
  }

  function markRead(id: string) {
    persistRead(new Set(readIds).add(id))
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className="relative rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
          aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
        >
          {unreadCount > 0 ? (
            <BellDot className="size-4" />
          ) : (
            <Bell className="size-4" />
          )}
          {unreadCount > 0 && (
            <span className="absolute right-1.5 top-1.5 flex size-2 items-center justify-center rounded-full bg-critical" />
          )}
        </Button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold">Notifications</span>
            {unreadCount > 0 && (
              <Badge variant="secondary" className="h-5 px-1.5 text-xs">
                {unreadCount}
              </Badge>
            )}
          </div>
          {unreadCount > 0 && (
            <Button
              variant="ghost"
              size="xs"
              onClick={markAllRead}
              className="h-auto gap-1 px-0 text-xs font-normal text-muted-foreground hover:bg-transparent hover:text-foreground"
            >
              <CheckCheck className="size-3" />
              Mark all read
            </Button>
          )}
        </div>

        <DropdownMenuSeparator className="m-0" />

        {notifications.length === 0 ? (
          <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
            <Bell className="size-8 text-muted-foreground/40" />
            <p className="text-sm text-muted-foreground">All clear</p>
          </div>
        ) : (
          <ScrollArea className="max-h-95">
            <div className="py-1">
              {notifications.map((n) => (
                <Button
                  key={n.id}
                  variant="ghost"
                  onClick={() => {
                    markRead(n.id)
                    navigate(n.serviceId ? { to: '/graph', search: { service: n.serviceId } } : { to: '/risks' })
                  }}
                  className={cn(
                    'h-auto w-full items-start justify-start gap-3 rounded-none px-4 py-3 text-left font-normal hover:bg-muted/60',
                    n.read && 'opacity-60',
                  )}
                >
                  <div className="mt-1.5 shrink-0">
                    <span
                      className={cn(
                        'flex size-2 rounded-full',
                        n.read ? 'bg-muted-foreground/30' : severityConfig[n.severity].dot,
                      )}
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className={cn('text-sm font-medium leading-snug', n.read && 'font-normal')}>
                      {n.title}
                    </p>
                    <span className="mt-0.5 block text-xs text-muted-foreground">{n.subtitle}</span>
                  </div>
                </Button>
              ))}
            </div>
          </ScrollArea>
        )}

        <DropdownMenuSeparator className="m-0" />
        <div className="px-4 py-2.5">
          <Link
            to="/risks"
            className="flex items-center gap-1.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            <ExternalLink className="size-3" />
            View all risks
          </Link>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
