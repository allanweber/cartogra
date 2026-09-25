import { useQuery } from '@tanstack/react-query'
import { createFileRoute, Link } from '@tanstack/react-router'
import { Network, X } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { z } from 'zod'

import { AppLayout } from '#/components/AppLayout'
import { DependencyGraph } from '#/components/DependencyGraph'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '#/components/ui/select'
import { Skeleton } from '#/components/ui/skeleton'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { ApiError, apiFetch } from '#/lib/api'
import { normalizeHealth } from '#/lib/registry-types'

import type { PageResult, RegistryTeam } from '#/lib/registry-types'
import type { DependencyType, Graph, GraphEdge, GraphNode } from '#/lib/topology-types'

const graphSearchSchema = z.object({
  service: z.string().optional(),
  type: z.enum(['DECLARED', 'OBSERVED']).optional(),
  team: z.string().optional(),
})

export const Route = createFileRoute('/_authenticated/graph')({
  component: GraphPage,
  validateSearch: graphSearchSchema,
})

const ALL_TEAMS = 'ALL'

/**
 * A→B and B→A between the same pair are distinct declared-dependency rows (the DB's
 * uniqueness constraint is directional), so a reciprocal edge would otherwise list the same
 * neighbor twice with an identical protocol badge. Collapse to one row per (neighbor, protocol).
 */
function dedupeNeighbors(
  entries: { node: GraphNode; edge: GraphEdge }[],
): { node: GraphNode; edge: GraphEdge }[] {
  const seen = new Set<string>()
  const result: { node: GraphNode; edge: GraphEdge }[] = []
  for (const entry of entries) {
    const key = `${entry.node.serviceId}|${entry.edge.protocol}`
    if (seen.has(key)) continue
    seen.add(key)
    result.push(entry)
  }
  return result
}

function GraphPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const [bannerDismissed, setBannerDismissed] = useState(false)

  const selectedServiceId = search.service ?? null
  const type = search.type ?? 'DECLARED'
  const teamId = search.team ?? null

  function setSelectedServiceId(id: string | null) {
    navigate({ search: (prev) => ({ ...prev, service: id ?? undefined }), replace: true })
  }
  function setType(next: DependencyType) {
    navigate({ search: (prev) => ({ ...prev, type: next === 'DECLARED' ? undefined : next }), replace: true })
  }
  function setTeamId(next: string | null) {
    navigate({ search: (prev) => ({ ...prev, team: next ?? undefined }), replace: true })
  }

  const { data: teamsPage } = useQuery({
    queryKey: ['teams'],
    queryFn: () => apiFetch<PageResult<RegistryTeam>>('/v1/registry/teams?limit=200'),
  })

  const { data: graph, isLoading, error } = useQuery({
    queryKey: ['graph', type, teamId],
    queryFn: () => {
      const params = new URLSearchParams({ type })
      if (teamId) params.set('teamId', teamId)
      return apiFetch<Graph>(`/v1/topology/graph?${params.toString()}`)
    },
    staleTime: 0,
  })

  useEffect(() => {
    setBannerDismissed(false)
  }, [type, teamId])

  const nodesById = useMemo(() => {
    const map = new Map<string, GraphNode>()
    graph?.nodes.forEach((node) => map.set(node.serviceId, node))
    return map
  }, [graph])

  const selectedNode = selectedServiceId ? (nodesById.get(selectedServiceId) ?? null) : null
  const neighbors: { node: GraphNode; edge: GraphEdge }[] =
    selectedNode && graph
      ? dedupeNeighbors(
          graph.edges
            .filter((edge) => edge.source === selectedNode.serviceId || edge.target === selectedNode.serviceId)
            .map((edge) => ({
              node: nodesById.get(edge.source === selectedNode.serviceId ? edge.target : edge.source),
              edge,
            }))
            .filter((entry): entry is { node: GraphNode; edge: GraphEdge } => !!entry.node),
        )
      : []

  return (
    <AppLayout
      title="Graph"
      description="A dependency map for blast radius, single points of failure, and drift visibility."
    >
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <ToggleGroup
          type="single"
          value={type}
          onValueChange={(value) => {
            if (value) setType(value as DependencyType)
          }}
          variant="outline"
          size="sm"
        >
          <ToggleGroupItem value="DECLARED">Declared</ToggleGroupItem>
          <ToggleGroupItem value="OBSERVED">Observed</ToggleGroupItem>
        </ToggleGroup>

        <Select
          value={teamId ?? ALL_TEAMS}
          onValueChange={(value) => setTeamId(value === ALL_TEAMS ? null : value)}
        >
          <SelectTrigger className="w-48" size="sm">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_TEAMS}>All teams</SelectItem>
            {(teamsPage?.items ?? []).map((team) => (
              <SelectItem key={team.id} value={team.id}>
                {team.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {type === 'OBSERVED' && (
        <Alert className="mb-4">
          <AlertDescription>
            Observed dependencies aren&apos;t collected yet — this view will populate once span data lands in
            Phase 3.
          </AlertDescription>
        </Alert>
      )}

      {graph?.truncated && !bannerDismissed && (
        <Alert className="mb-4">
          <AlertDescription className="flex items-center justify-between gap-4">
            <span>This graph has been truncated — not every service is shown.</span>
            <Button variant="ghost" size="icon-sm" onClick={() => setBannerDismissed(true)}>
              <X className="size-3.5" />
              <span className="sr-only">Dismiss</span>
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {isLoading && (
        <div className="space-y-4">
          <Skeleton className="h-8 w-64" />
          <Skeleton className="h-[600px] w-full rounded-xl" />
        </div>
      )}

      {!isLoading && (error || !graph) && (
        <Alert variant="destructive">
          <AlertDescription>
            {error?.message ?? 'Failed to load the graph.'}
            {error instanceof ApiError && ` (trace: ${error.traceId})`}
          </AlertDescription>
        </Alert>
      )}

      {!isLoading && graph && graph.nodes.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border py-24 text-center">
          <Network className="mb-3 size-8 text-muted-foreground/50" />
          <p className="text-sm font-medium">No services to graph yet.</p>
          <p className="mt-1 text-sm text-muted-foreground">
            Register a service in the catalog to start building your dependency graph.
          </p>
          <Button asChild variant="outline" size="sm" className="mt-4">
            <Link to="/catalog">Go to catalog</Link>
          </Button>
        </div>
      )}

      {!isLoading && graph && graph.nodes.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
          <div className="h-[calc(100vh-260px)] min-h-[480px] rounded-xl border border-border bg-card">
            <DependencyGraph graph={graph} selectedServiceId={selectedServiceId} onSelectNode={setSelectedServiceId} />
          </div>

          <div className="space-y-4">
            {selectedNode ? (
              <Card>
                <CardHeader className="pb-2 pt-5">
                  <CardTitle className="text-sm font-semibold">{selectedNode.name}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4 pb-5 pt-0 text-sm">
                  <div className="space-y-1">
                    <p className="text-xs uppercase tracking-wide text-muted-foreground">Health</p>
                    <p className="capitalize">{normalizeHealth(selectedNode.healthStatus)}</p>
                  </div>
                  {selectedNode.tier && (
                    <div className="space-y-1">
                      <p className="text-xs uppercase tracking-wide text-muted-foreground">Tier</p>
                      <p className="capitalize">{selectedNode.tier.toLowerCase()}</p>
                    </div>
                  )}
                  <div className="space-y-1">
                    <p className="text-xs uppercase tracking-wide text-muted-foreground">Neighbors</p>
                    {neighbors.length === 0 ? (
                      <p className="text-muted-foreground">No connected services.</p>
                    ) : (
                      <ul className="space-y-2">
                        {neighbors.map(({ node, edge }) => (
                          <li key={`${node.serviceId}-${edge.protocol}`}>
                            <div className="flex items-center gap-2">
                              <Link
                                to="/catalog/$serviceId"
                                params={{ serviceId: node.serviceId }}
                                className="text-primary hover:underline"
                              >
                                {node.name}
                              </Link>
                              <span className="rounded-md border border-border bg-background px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                                {edge.protocol}
                              </span>
                            </div>
                            {edge.metadata && <p className="text-xs text-muted-foreground">{edge.metadata}</p>}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                  <Link
                    to="/catalog/$serviceId"
                    params={{ serviceId: selectedNode.serviceId }}
                    className="block text-primary hover:underline"
                  >
                    View in catalog →
                  </Link>
                </CardContent>
              </Card>
            ) : (
              <Card className="border-dashed">
                <CardContent className="py-8 text-center text-sm text-muted-foreground">
                  Select a node to see its details.
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      )}
    </AppLayout>
  )
}
