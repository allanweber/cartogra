import { TriangleAlert } from 'lucide-react'

import { badgeVariants } from '#/components/ui/badge'
import { Popover, PopoverContent, PopoverTrigger } from '#/components/ui/popover'
import { cn } from '#/lib/utils'

import type { Spof } from '#/lib/topology-types'

/**
 * A drill-in badge for single points of failure — used both on `/graph` (unfiltered, shows every
 * SPOF in the tenant) and on a single node's own detail (`InspectorPanel`, `catalog.$serviceId`,
 * via `onlyForServiceId`, which narrows to whether that node itself is flagged). Renders nothing
 * when there's nothing relevant to show. Mirrors `CycleBadge` — the two standing-risk badges
 * share a shape but use distinct icon/color so they stay visually distinguishable.
 */
export function SpofBadge({
  spofs,
  nodesById,
  onlyForServiceId,
  threshold,
  rationale,
}: {
  spofs: Spof[]
  nodesById: Map<string, { name: string }>
  onlyForServiceId?: string
  threshold?: number
  rationale?: string
}) {
  const relevant = onlyForServiceId ? spofs.filter((spof) => spof.serviceId === onlyForServiceId) : spofs
  if (relevant.length === 0) return null

  const nameFor = (serviceId: string) => nodesById.get(serviceId)?.name ?? serviceId
  const highestSeverity = relevant.some((spof) => spof.severity === 'critical') ? 'critical' : 'warning'

  return (
    <Popover>
      <PopoverTrigger
        className={cn(
          badgeVariants({ variant: 'outline' }),
          'relative cursor-pointer border-current after:absolute after:-inset-x-1 after:-inset-y-3.5',
          highestSeverity === 'critical' ? 'text-critical' : 'text-warning',
        )}
        aria-label={`${relevant.length} single point of ${relevant.length === 1 ? 'failure' : 'failures'}. View details.`}
      >
        <TriangleAlert aria-hidden="true" />
        {relevant.length} {relevant.length === 1 ? 'single point of failure' : 'single points of failure'}
      </PopoverTrigger>
      <PopoverContent align="start" className="w-auto max-w-sm">
        <div className="space-y-2">
          <p className="text-sm font-semibold">
            {relevant.length === 1 ? 'Single point of failure' : 'Single points of failure'}
          </p>
          <ul className="space-y-1.5 text-xs text-muted-foreground">
            {relevant.map((spof) => (
              <li key={spof.serviceId} className="break-words">
                {nameFor(spof.serviceId)} — {spof.fanIn} dependent{spof.fanIn === 1 ? '' : 's'}
              </li>
            ))}
          </ul>
          {(rationale || threshold !== undefined) && (
            <p className="border-t border-border pt-2 text-xs text-muted-foreground">
              {threshold !== undefined && <span className="font-medium">Flagged at {threshold}+ dependents. </span>}
              {rationale}
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  )
}
