import { Link } from '@tanstack/react-router'
import { useState } from 'react'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from '#/components/ui/card'
import { ScrollArea } from '#/components/ui/scroll-area'
import { Skeleton } from '#/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '#/components/ui/tabs'
import { ApiError } from '#/lib/api'
import { normalizeHealth } from '#/lib/registry-types'
import { cn } from '#/lib/utils'

import type { BlastRadius, BlastRadiusDirectionResult, BlastRadiusEntry, GraphNode } from '#/lib/topology-types'

// Mirrors BlastRadiusService.MAX_NODES_PER_DIRECTION on the backend — the cap is a fixed
// constant per direction, not something derivable from a single response's entry counts.
const NODE_CAP_PER_DIRECTION = 200

function healthDotClass(healthStatus: GraphNode['healthStatus']): string {
  const health = normalizeHealth(healthStatus)
  if (health === 'down') return 'bg-critical'
  if (health === 'degraded') return 'bg-warning'
  return 'bg-success'
}

function groupByDistance(entries: BlastRadiusEntry[]): [number, BlastRadiusEntry[]][] {
  const byDistance = new Map<number, BlastRadiusEntry[]>()
  entries.forEach((entry) => {
    const group = byDistance.get(entry.distance) ?? []
    group.push(entry)
    byDistance.set(entry.distance, group)
  })
  return [...byDistance.entries()].sort(([a], [b]) => a - b)
}

function BlastRadiusEntryRow({ entry, teamMap }: { entry: BlastRadiusEntry; teamMap: Map<string, string> }) {
  const teamName = teamMap.get(entry.teamId ?? '') ?? 'Unassigned'
  return (
    <li className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2.5">
      <div className="flex min-w-0 items-center gap-2">
        <span className={cn('size-2 shrink-0 rounded-full', healthDotClass(entry.healthStatus))} aria-hidden="true" />
        <Link
          to="/catalog/$serviceId"
          params={{ serviceId: entry.serviceId }}
          className="truncate text-sm font-medium hover:text-primary hover:underline"
        >
          {entry.name}
        </Link>
      </div>
      <span className="shrink-0 text-xs text-muted-foreground">{teamName}</span>
    </li>
  )
}

function BlastRadiusDirectionSection({
  title,
  result,
  ringColorClassName,
  teamMap,
  maxDepth,
}: {
  title: string
  result: BlastRadiusDirectionResult
  ringColorClassName: string
  teamMap: Map<string, string>
  maxDepth: number
}) {
  const groups = groupByDistance(result.entries)
  return (
    <div className="space-y-2">
      <p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-widest text-muted-foreground/60">
        <span className={cn('size-2 rounded-full', ringColorClassName)} aria-hidden="true" />
        {title}
      </p>
      {groups.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border px-3 py-4 text-center text-sm text-muted-foreground">
          No affected services in this direction.
        </p>
      ) : (
        <div className="space-y-3">
          {groups.map(([distance, entries]) => (
            <div key={distance} className="space-y-1.5">
              <div className="flex items-center gap-2">
                <Badge variant="outline">
                  {distance} {distance === 1 ? 'hop' : 'hops'} away
                </Badge>
              </div>
              <ul className="space-y-1.5">
                {entries.map((entry) => (
                  <BlastRadiusEntryRow key={entry.serviceId} entry={entry} teamMap={teamMap} />
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
      {result.depthTruncated && (
        <Alert>
          <AlertDescription>
            Truncated at {maxDepth} hops — {result.nodesBeyondDepth}+ services beyond this depth aren&apos;t shown.
          </AlertDescription>
        </Alert>
      )}
      {result.nodeCapTruncated && (
        <Alert>
          <AlertDescription>
            Showing the closest {NODE_CAP_PER_DIRECTION} services — {result.nodesBeyondCap} more exist beyond this
            list.
          </AlertDescription>
        </Alert>
      )}
    </div>
  )
}

function BlastRadiusTab({
  blastRadius,
  isLoading,
  error,
  teamMap,
}: {
  blastRadius: BlastRadius | undefined
  isLoading: boolean
  error: Error | null
  teamMap: Map<string, string>
}) {
  if (isLoading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-20 w-full rounded-lg" />
        <Skeleton className="h-20 w-full rounded-lg" />
      </div>
    )
  }

  if (error || !blastRadius) {
    return (
      <Alert variant="destructive">
        <AlertDescription>
          {error?.message ?? 'Failed to load the blast radius.'}
          {error instanceof ApiError && ` (trace: ${error.traceId})`}
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <ScrollArea className="h-full">
      <div className="space-y-5 pr-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5">
            <span className="size-2 rounded-full bg-ring" aria-hidden="true" />
            Selected
          </span>
          <span className="flex items-center gap-1.5">
            <span className="size-2 rounded-full bg-blast-upstream" aria-hidden="true" />
            Upstream
          </span>
          <span className="flex items-center gap-1.5">
            <span className="size-2 rounded-full bg-blast-downstream" aria-hidden="true" />
            Downstream
          </span>
        </div>

        <BlastRadiusDirectionSection
          title="Downstream — impacted if this service fails"
          result={blastRadius.downstream}
          ringColorClassName="bg-blast-downstream"
          teamMap={teamMap}
          maxDepth={blastRadius.maxDepth}
        />
        <BlastRadiusDirectionSection
          title="Upstream — what this service depends on"
          result={blastRadius.upstream}
          ringColorClassName="bg-blast-upstream"
          teamMap={teamMap}
          maxDepth={blastRadius.maxDepth}
        />
      </div>
    </ScrollArea>
  )
}

export function InspectorPanel({
  node,
  teamMap,
  blastRadius,
  isBlastRadiusLoading,
  blastRadiusError,
}: {
  node: GraphNode
  teamMap: Map<string, string>
  blastRadius: BlastRadius | undefined
  isBlastRadiusLoading: boolean
  blastRadiusError: Error | null
}) {
  const [activeTab, setActiveTab] = useState<'details' | 'blast-radius'>('blast-radius')
  const teamName = teamMap.get(node.teamId ?? '') ?? null

  return (
    <Card className="h-full">
      <CardHeader className="shrink-0 pb-2 pt-5">
        <CardTitle className="text-sm font-semibold">{node.name}</CardTitle>
      </CardHeader>
      <CardContent className="flex min-h-0 flex-1 flex-col pb-3 pt-0">
        <Tabs
          value={activeTab}
          onValueChange={(value) => setActiveTab(value as 'details' | 'blast-radius')}
          className="h-full min-h-0"
        >
          <TabsList className="shrink-0">
            <TabsTrigger value="details">Details</TabsTrigger>
            <TabsTrigger value="blast-radius">Blast Radius</TabsTrigger>
          </TabsList>
          <TabsContent value="details" className="space-y-4 pt-3 text-sm">
            <div className="space-y-1">
              <p className="text-xs uppercase tracking-wide text-muted-foreground">Health</p>
              <p className="capitalize">{normalizeHealth(node.healthStatus)}</p>
            </div>
            {node.tier && (
              <div className="space-y-1">
                <p className="text-xs uppercase tracking-wide text-muted-foreground">Tier</p>
                <p className="capitalize">{node.tier.toLowerCase()}</p>
              </div>
            )}
            <div className="space-y-1">
              <p className="text-xs uppercase tracking-wide text-muted-foreground">Team</p>
              <p>{teamName ?? 'Unassigned'}</p>
            </div>
          </TabsContent>
          <TabsContent value="blast-radius" className="min-h-0 pt-3">
            <BlastRadiusTab
              blastRadius={blastRadius}
              isLoading={isBlastRadiusLoading}
              error={blastRadiusError}
              teamMap={teamMap}
            />
          </TabsContent>
        </Tabs>
      </CardContent>
      <CardFooter className="shrink-0">
        <Link to="/catalog/$serviceId" params={{ serviceId: node.serviceId }} className="text-sm text-primary hover:underline">
          View in catalog →
        </Link>
      </CardFooter>
    </Card>
  )
}
