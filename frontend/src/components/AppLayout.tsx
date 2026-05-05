import { Link, useRouterState } from '@tanstack/react-router'
import {
  Activity,
  Brain,
  FolderKanban,
  GitBranch,
  Menu,
  ShieldCheck,
} from 'lucide-react'

import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Separator } from '#/components/ui/separator'
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
import { cn } from '#/lib/utils'

import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'

interface AppLayoutProps {
  title: string
  description: string
  eyebrow?: string
  actions?: ReactNode
  children: ReactNode
}

interface NavigationItem {
  label: string
  to: '/catalog' | '/graph' | '/contracts' | '/intelligence' | '/ops'
  icon: LucideIcon
}

const navigationItems: NavigationItem[] = [
  { label: 'Catalog', to: '/catalog', icon: FolderKanban },
  { label: 'Graph', to: '/graph', icon: GitBranch },
  { label: 'Contracts', to: '/contracts', icon: ShieldCheck },
  { label: 'Intelligence', to: '/intelligence', icon: Brain },
  { label: 'Operations', to: '/ops', icon: Activity },
]

export function AppLayout({
  title,
  description,
  eyebrow = 'Cartogra',
  actions,
  children,
}: AppLayoutProps) {
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  })

  return (
    <div className="min-h-screen text-foreground">
      <div className="mx-auto flex min-h-screen w-full max-w-[1600px] gap-0 px-4 py-4 sm:px-6 lg:px-8">
        <aside className="sticky top-4 hidden h-[calc(100vh-2rem)] w-72 shrink-0 flex-col rounded-[calc(var(--radius)*1.8)] border border-sidebar-border bg-sidebar/90 p-4 shadow-[0_0_0_1px_color-mix(in_oklab,var(--border)_85%,transparent),0_24px_60px_-36px_color-mix(in_oklab,var(--foreground)_22%,transparent)] backdrop-blur md:flex">
          <SidebarContent pathname={pathname} />
        </aside>

        <div className="flex min-h-[calc(100vh-2rem)] min-w-0 flex-1 flex-col overflow-hidden rounded-[calc(var(--radius)*1.8)] border border-border/80 bg-card/90 shadow-[0_0_0_1px_color-mix(in_oklab,var(--border)_84%,transparent),0_30px_80px_-45px_color-mix(in_oklab,var(--foreground)_30%,transparent)] backdrop-blur">
          <header className="sticky top-0 z-10 border-b border-border/70 bg-card/90 px-4 py-4 backdrop-blur sm:px-6">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div className="flex items-start gap-3">
                <div className="md:hidden">
                  <NavigationSheet pathname={pathname} />
                </div>
                <div className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="outline" className="rounded-full px-2.5 py-1 text-[11px] tracking-[0.16em] uppercase">
                      {eyebrow}
                    </Badge>
                    <Badge variant="secondary" className="rounded-full px-2.5 py-1 text-[11px]">
                      Phase 0 shell
                    </Badge>
                  </div>
                  <div>
                    <h1 className="text-2xl font-semibold tracking-[-0.02em] sm:text-[2rem]">
                      {title}
                    </h1>
                    <p className="mt-1 max-w-3xl text-sm text-muted-foreground sm:text-[0.95rem]">
                      {description}
                    </p>
                  </div>
                </div>
              </div>
              {actions ? <div className="flex flex-wrap items-center gap-3">{actions}</div> : null}
            </div>
          </header>

          <main className="flex-1 px-4 py-6 sm:px-6 sm:py-8">{children}</main>
        </div>
      </div>
    </div>
  )
}

function SidebarContent({ pathname }: { pathname: string }) {
  return (
    <div className="flex h-full flex-col">
      <div className="space-y-3 px-2 pb-4">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-[11px] font-medium tracking-[0.22em] text-muted-foreground uppercase">
              Cartogra
            </p>
            <h2 className="mt-1 text-xl font-semibold tracking-[-0.02em]">Service intelligence</h2>
          </div>
          <Badge className="rounded-full px-2.5 py-1">Alpha</Badge>
        </div>
        <p className="text-sm leading-6 text-muted-foreground">
          A calm operating frame for dependency risk, ownership clarity, and contract visibility.
        </p>
      </div>

      <Separator className="mb-4" />

      <nav className="space-y-1.5">
        {navigationItems.map((item) => {
          const Icon = item.icon
          const isActive =
            pathname === item.to || pathname.startsWith(`${item.to}/`)

          return (
            <Tooltip key={item.to}>
              <TooltipTrigger asChild>
                <Link
                  to={item.to}
                  className={cn(
                    'group flex items-center gap-3 rounded-xl px-3 py-3 text-sm transition-colors',
                    isActive
                      ? 'bg-sidebar-primary text-sidebar-primary-foreground shadow-sm'
                      : 'text-sidebar-foreground/78 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground',
                  )}
                >
                  <span
                    className={cn(
                      'flex size-9 items-center justify-center rounded-lg border transition-colors',
                      isActive
                        ? 'border-sidebar-primary-foreground/20 bg-sidebar-primary-foreground/10'
                        : 'border-sidebar-border bg-background/70 group-hover:border-sidebar-accent-foreground/10',
                    )}
                  >
                    <Icon className="size-4" />
                  </span>
                  <span className="flex-1 font-medium">{item.label}</span>
                </Link>
              </TooltipTrigger>
              <TooltipContent side="right">Open {item.label}</TooltipContent>
            </Tooltip>
          )
        })}
      </nav>

      <div className="mt-auto rounded-2xl border border-sidebar-border bg-background/80 p-4">
        <p className="text-xs font-medium tracking-[0.16em] text-muted-foreground uppercase">
          Phase guide
        </p>
        <p className="mt-2 text-sm leading-6 text-muted-foreground">
          The shell is live first so later feature surfaces inherit stable navigation, recovery, and state handling.
        </p>
      </div>
    </div>
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
      <SheetContent side="left" className="w-[290px] border-sidebar-border bg-sidebar px-0">
        <SheetHeader className="px-5 text-left">
          <SheetTitle className="text-xl tracking-[-0.02em]">Cartogra</SheetTitle>
          <SheetDescription>
            Decision-first navigation for the Phase 0 shell.
          </SheetDescription>
        </SheetHeader>
        <div className="mt-6 px-3">
          <SidebarContent pathname={pathname} />
        </div>
      </SheetContent>
    </Sheet>
  )
}