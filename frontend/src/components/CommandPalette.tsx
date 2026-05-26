import { Dialog } from 'radix-ui'
import {
  Activity,
  Brain,
  FileText,
  FolderKanban,
  GitBranch,
  LayoutDashboard,
  Search,
  Settings,
  ShieldCheck,
  TriangleAlert,
  Users,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'

import { MOCK_SERVICES, MOCK_TEAMS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

interface CommandItem {
  id: string
  label: string
  subtitle?: string
  icon: React.ReactNode
  action: () => void
  group: string
}

const PAGE_ITEMS = [
  { label: 'Dashboard', to: '/dashboard', icon: <LayoutDashboard className="size-4" /> },
  { label: 'Catalog', to: '/catalog', icon: <FolderKanban className="size-4" /> },
  { label: 'Graph', to: '/graph', icon: <GitBranch className="size-4" /> },
  { label: 'Risks', to: '/risks', icon: <TriangleAlert className="size-4" /> },
  { label: 'Contracts', to: '/contracts', icon: <ShieldCheck className="size-4" /> },
  { label: 'Teams', to: '/teams', icon: <Users className="size-4" /> },
  { label: 'Timeline', to: '/timeline', icon: <Activity className="size-4" /> },
  { label: 'Intelligence', to: '/intelligence', icon: <Brain className="size-4" /> },
  { label: 'Operations', to: '/ops', icon: <FileText className="size-4" /> },
  { label: 'Settings', to: '/settings', icon: <Settings className="size-4" /> },
]

interface CommandPaletteProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CommandPalette({ open, onOpenChange }: CommandPaletteProps) {
  const [query, setQuery] = useState('')
  const [activeIdx, setActiveIdx] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()

  function close() {
    onOpenChange(false)
    setQuery('')
    setActiveIdx(0)
  }

  const allItems = useMemo<CommandItem[]>(() => {
    const pages: CommandItem[] = PAGE_ITEMS.map((p) => ({
      id: `page-${p.to}`,
      label: p.label,
      subtitle: 'Navigate',
      icon: p.icon,
      action: () => { navigate({ to: p.to as never }); close() },
      group: 'Pages',
    }))

    const services: CommandItem[] = MOCK_SERVICES.map((s) => ({
      id: `svc-${s.id}`,
      label: s.name,
      subtitle: `${s.owner ?? 'Unowned'} · ${s.health}`,
      icon: <FolderKanban className="size-4" />,
      action: () => { navigate({ to: '/catalog/$serviceId', params: { serviceId: s.id } }); close() },
      group: 'Services',
    }))

    const teams: CommandItem[] = MOCK_TEAMS.map((t) => ({
      id: `team-${t.id}`,
      label: t.name,
      subtitle: `${t.members} members · ${t.lead}`,
      icon: <Users className="size-4" />,
      action: () => { navigate({ to: '/teams' }); close() },
      group: 'Teams',
    }))

    return [...pages, ...services, ...teams]
  }, [])

  const filtered = query.trim()
    ? allItems.filter(
        (item) =>
          item.label.toLowerCase().includes(query.toLowerCase()) ||
          item.subtitle?.toLowerCase().includes(query.toLowerCase()),
      )
    : allItems.filter((item) => item.group === 'Pages')

  const grouped = filtered.reduce<Record<string, CommandItem[]>>((acc, item) => {
    ;(acc[item.group] ??= []).push(item)
    return acc
  }, {})

  const flatFiltered = Object.values(grouped).flat()

  useEffect(() => {
    setActiveIdx(0)
  }, [query])


  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'ArrowDown') {
        e.preventDefault()
        setActiveIdx((i) => Math.min(i + 1, flatFiltered.length - 1))
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        setActiveIdx((i) => Math.max(i - 1, 0))
      } else if (e.key === 'Enter') {
        flatFiltered[activeIdx]?.action()
      }
    },
    [flatFiltered, activeIdx],
  )

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 backdrop-blur-sm data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />
        <Dialog.Content
          className="fixed left-1/2 top-[12%] z-50 w-full max-w-2xl -translate-x-1/2 overflow-hidden rounded-2xl border border-border bg-popover shadow-2xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=open]:slide-in-from-left-1/2"
          aria-label="Command palette"
          onKeyDown={handleKeyDown}
          onOpenAutoFocus={(e) => { e.preventDefault(); inputRef.current?.focus() }}
        >
          {/* Search input */}
          <div className="flex items-center gap-3 border-b border-border px-4 py-3.5">
            <Search className="size-4 shrink-0 text-muted-foreground" />
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search pages, services, teams…"
              className="flex-1 bg-transparent text-sm outline-none placeholder:text-muted-foreground"
            />
            <kbd className="hidden rounded border border-border bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground sm:block">
              ESC
            </kbd>
          </div>

          {/* Results */}
          <div className="max-h-130 overflow-y-auto py-2">
            {flatFiltered.length === 0 ? (
              <div className="px-4 py-8 text-center text-sm text-muted-foreground">
                No results for &ldquo;{query}&rdquo;
              </div>
            ) : (
              Object.entries(grouped).map(([group, items]) => (
                <div key={group}>
                  <p className="px-4 pb-1 pt-2 text-[10px] font-semibold uppercase tracking-widest text-muted-foreground">
                    {group}
                  </p>
                  {items.map((item) => {
                    const globalIdx = flatFiltered.indexOf(item)
                    return (
                      <button
                        key={item.id}
                        onClick={item.action}
                        onMouseEnter={() => setActiveIdx(globalIdx)}
                        className={cn(
                          'flex w-full items-center gap-3 px-4 py-2.5 text-left text-sm transition-colors',
                          globalIdx === activeIdx
                            ? 'bg-primary text-primary-foreground'
                            : 'text-foreground hover:bg-muted',
                        )}
                      >
                        <span
                          className={cn(
                            'shrink-0',
                            globalIdx === activeIdx
                              ? 'text-primary-foreground'
                              : 'text-muted-foreground',
                          )}
                        >
                          {item.icon}
                        </span>
                        <span className="flex-1 font-medium">{item.label}</span>
                        {item.subtitle && (
                          <span
                            className={cn(
                              'text-xs',
                              globalIdx === activeIdx
                                ? 'text-primary-foreground/70'
                                : 'text-muted-foreground',
                            )}
                          >
                            {item.subtitle}
                          </span>
                        )}
                      </button>
                    )
                  })}
                </div>
              ))
            )}
          </div>

          <div className="border-t border-border px-4 py-2.5">
            <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
              <span className="flex items-center gap-1">
                <kbd className="rounded border border-border bg-muted px-1.5 py-0.5">↑↓</kbd> navigate
              </span>
              <span className="flex items-center gap-1">
                <kbd className="rounded border border-border bg-muted px-1.5 py-0.5">↵</kbd> open
              </span>
              <span className="flex items-center gap-1">
                <kbd className="rounded border border-border bg-muted px-1.5 py-0.5">ESC</kbd> close
              </span>
            </div>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
