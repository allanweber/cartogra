import { Bell, BellDot, CheckCheck, ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { Link } from '@tanstack/react-router'

import { Badge } from '#/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '#/components/ui/dropdown-menu'
import { ScrollArea } from '#/components/ui/scroll-area'
import { MOCK_RISKS, MOCK_EVENTS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

interface Notification {
  id: string
  kind: 'risk' | 'event'
  title: string
  subtitle: string
  time: string
  severity: 'critical' | 'warning' | 'info' | 'success'
  read: boolean
}

function buildNotifications(): Notification[] {
  const risks: Notification[] = MOCK_RISKS.filter(
    (r) => r.severity !== 'info',
  ).map((r) => ({
    id: `risk-${r.id}`,
    kind: 'risk' as const,
    title: r.title,
    subtitle: r.services.join(', '),
    time: 'Active',
    severity: r.severity === 'critical' ? 'critical' : 'warning',
    read: false,
  }))

  const events: Notification[] = MOCK_EVENTS.filter(
    (e) => e.status === 'error' || e.status === 'warning',
  ).map((e) => ({
    id: `event-${e.id}`,
    kind: 'event' as const,
    title: e.msg,
    subtitle: e.type.toUpperCase(),
    time: e.time,
    severity: e.status === 'error' ? 'critical' : 'warning',
    read: false,
  }))

  return [...risks, ...events]
}


const severityConfig: Record<Notification['severity'], { dot: string; text: string }> = {
  critical: { dot: 'bg-[oklch(0.58_0.23_28)]', text: 'text-[oklch(0.58_0.23_28)]' },
  warning: { dot: 'bg-[oklch(0.65_0.18_60)]', text: 'text-[oklch(0.65_0.18_60)]' },
  info: { dot: 'bg-[oklch(0.60_0.18_240)]', text: 'text-[oklch(0.60_0.18_240)]' },
  success: { dot: 'bg-[oklch(0.55_0.18_145)]', text: 'text-[oklch(0.55_0.18_145)]' },
}

export function NotificationBell() {
  const [notifications, setNotifications] = useState<Notification[]>(buildNotifications)
  const unreadCount = notifications.filter((n) => !n.read).length

  function markAllRead() {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })))
  }

  function markRead(id: string) {
    setNotifications((prev) =>
      prev.map((n) => (n.id === id ? { ...n, read: true } : n)),
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          className="relative flex size-9 items-center justify-center rounded-lg text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={`Notifications${unreadCount > 0 ? `, ${unreadCount} unread` : ''}`}
        >
          {unreadCount > 0 ? (
            <BellDot className="size-4" />
          ) : (
            <Bell className="size-4" />
          )}
          {unreadCount > 0 && (
            <span className="absolute right-1.5 top-1.5 flex size-2 items-center justify-center rounded-full bg-[oklch(0.58_0.23_28)]" />
          )}
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold">Notifications</span>
            {unreadCount > 0 && (
              <Badge variant="secondary" className="h-5 px-1.5 text-[11px]">
                {unreadCount}
              </Badge>
            )}
          </div>
          {unreadCount > 0 && (
            <button
              onClick={markAllRead}
              className="flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
            >
              <CheckCheck className="size-3" />
              Mark all read
            </button>
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
                <button
                  key={n.id}
                  onClick={() => markRead(n.id)}
                  className={cn(
                    'flex w-full items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/60',
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
                    <div className="mt-0.5 flex items-center gap-1.5">
                      <span className="text-[11px] text-muted-foreground">{n.subtitle}</span>
                      <span className="text-[11px] text-muted-foreground">·</span>
                      <span className="text-[11px] text-muted-foreground">{n.time}</span>
                    </div>
                  </div>
                </button>
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
