import { Repeat } from 'lucide-react'

import { badgeVariants } from '#/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '#/components/ui/popover'
import { cn } from '#/lib/utils'

import type { Cycle } from '#/lib/topology-types'

function cycleLabel(cycle: Cycle, nameFor: (serviceId: string) => string): string {
  const names = cycle.members.map(nameFor)
  return [...names, names[0]].join(' → ')
}

/**
 * A drill-in badge for detected dependency cycles — used both on `/graph` (unfiltered, shows
 * every cycle in the tenant) and on a single node's own detail (`InspectorPanel`,
 * `catalog.$serviceId`, via `onlyForServiceId`, which narrows to the cycles that node belongs
 * to). Renders nothing when there's nothing relevant to show.
 */
export function CycleBadge({
  cycles,
  nodesById,
  onlyForServiceId,
  truncated = false,
}: {
  cycles: Cycle[]
  nodesById: Map<string, { name: string }>
  onlyForServiceId?: string
  truncated?: boolean
}) {
  const relevant = onlyForServiceId ? cycles.filter((cycle) => cycle.members.includes(onlyForServiceId)) : cycles
  if (relevant.length === 0) return null

  const countLabel = `${relevant.length}${truncated ? '+' : ''}`
  const nameFor = (serviceId: string) => nodesById.get(serviceId)?.name ?? serviceId

  return (
    <Popover>
      <PopoverTrigger
        className={cn(badgeVariants({ variant: 'outline' }), 'relative cursor-pointer border-current text-critical after:absolute after:-inset-x-1 after:-inset-y-3.5')}
        aria-label={`${countLabel} dependency ${relevant.length === 1 && !truncated ? 'cycle' : 'cycles'}. View details.`}
      >
        <Repeat aria-hidden="true" />
        {countLabel} {relevant.length === 1 && !truncated ? 'cycle' : 'cycles'}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto max-w-sm">
        <div className="space-y-2">
          <p className="text-sm font-semibold">{relevant.length === 1 ? 'Dependency cycle' : 'Dependency cycles'}</p>
          <ul className="space-y-1.5 text-xs text-muted-foreground">
            {relevant.map((cycle) => (
              <li key={cycle.members.join(',')} className="break-words">
                {cycleLabel(cycle, nameFor)}
              </li>
            ))}
          </ul>
          {truncated && (
            <p className="border-t border-border pt-2 text-xs text-muted-foreground">
              Cycle detection hit its cap — more cycles exist than are listed.
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}
