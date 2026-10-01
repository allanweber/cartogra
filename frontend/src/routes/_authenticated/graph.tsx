import { useQuery } from '@tanstack/react-query'
import { createFileRoute, Link } from '@tanstack/react-router'
import { Network, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { z } from 'zod'

import { AppLayout } from '#/components/AppLayout'
import { CycleBadge } from '#/components/CycleBadge'
import { DependencyGraph } from '#/components/DependencyGraph'
import { InspectorPanel } from '#/components/InspectorPanel'
import { SpofBadge } from '#/components/SpofBadge'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Card, CardContent } from '#/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '#/components/ui/select'
import { Skeleton } from '#/components/ui/skeleton'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { ApiError, apiFetch } from '#/lib/api'
import { cn } from '#/lib/utils'

import type { BlastRadiusHighlightMap } from '#/components/DependencyGraph'
import type { PageResult, RegistryTeam } from '#/lib/registry-types'
import type { BlastRadius, Cycle, Cycles, Spof, DependencyType, Graph, GraphNode, Spofs } from '#/lib/topology-types'

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
const NO_CYCLES: Cycle[] = []
const NO_SPOFS: Spof[] = []

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

  const teamMap = useMemo(() => new Map((teamsPage?.items ?? []).map((t) => [t.id, t.name])), [teamsPage])

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
  const [sheetExpanded, setSheetExpanded] = useState(false)
  const graphRef = useRef<HTMLDivElement>(null)
  // isLoading is already provably false whenever graph is defined (TanStack Query v5's
  // UseQueryResult is a discriminated union on status — a defined `data` means isLoading
  // can't be true), so checking it here would be dead weight, not a real guard.
  const selectionMissing = !!selectedServiceId && !!graph && !selectedNode

  useEffect(() => {
    if (!selectedServiceId || !window.matchMedia('(max-width: 1023px)').matches) return
    graphRef.current?.scrollIntoView({ block: 'start' })
  }, [selectedServiceId])

  useEffect(() => {
    if (!selectedServiceId) return
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setSelectedServiceId(null)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [selectedServiceId])

  const {
    data: blastRadius,
    isLoading: isBlastRadiusLoading,
    error: blastRadiusError,
  } = useQuery({
    queryKey: ['blast-radius', selectedServiceId],
    queryFn: () => apiFetch<BlastRadius>(`/v1/topology/blast-radius/${selectedServiceId}`),
    enabled: !!selectedServiceId && !selectionMissing,
    staleTime: 0,
  })

  const blastRadiusHighlight = useMemo<BlastRadiusHighlightMap | null>(() => {
    if (!blastRadius) return null
    const map: BlastRadiusHighlightMap = new Map()
    blastRadius.upstream.entries.forEach((e) => map.set(e.serviceId, 'upstream'))
    // Downstream wins on the canvas if a node is both (grilled and confirmed) — the panel's
    // two sections independently show both, so the cycle fact isn't hidden, only the
    // single-ring canvas summary picks one.
    blastRadius.downstream.entries.forEach((e) => map.set(e.serviceId, 'downstream'))
    return map
  }, [blastRadius])

  // Best-effort: a failed cycle fetch degrades to "no badges" rather than an Alert, since the
  // graph itself (not cycle detection) is this page's primary content — same treatment as
  // teamsPage above. staleTime: 0 like graph/blast-radius above — the QueryClient's global
  // default is 5 minutes, which would otherwise cache an empty pre-mutation result as "fresh"
  // and never refetch after a dependency change closes a cycle.
  const { data: cyclesData } = useQuery({
    queryKey: ['cycles', type],
    queryFn: () => apiFetch<Cycles>(`/v1/topology/cycles?type=${type}`),
    staleTime: 0,
  })
  const cycles = cyclesData?.cycles ?? NO_CYCLES
  const cycleMemberIds = useMemo(() => new Set(cycles.flatMap((c) => c.members)), [cycles])

  // Same best-effort-degrade-on-failure treatment as cycles above — a failed SPOF fetch just
  // means no badges, not a page-level Alert.
  const { data: spofsData } = useQuery({
    queryKey: ['spofs'],
    queryFn: () => apiFetch<Spofs>('/v1/topology/spofs'),
    staleTime: 0,
  })
  const spofs = spofsData?.items ?? NO_SPOFS
  const spofServiceIds = useMemo(() => new Set(spofs.map((s) => s.serviceId)), [spofs])

  return (
    <AppLayout
      title="Graph"
      description="A dependency map for blast radius, single points of failure, and drift visibility."
    >
      <div className="mb-4 flex shrink-0 flex-wrap items-center gap-3">
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
          <SelectTrigger className="w-48" size="sm" aria-label="Team filter">
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

        <CycleBadge cycles={cycles} nodesById={nodesById} />
        <SpofBadge
          spofs={spofs}
          nodesById={nodesById}
          threshold={spofsData?.threshold}
          rationale={spofsData?.rationale}
        />
      </div>

      {type === 'OBSERVED' && (
        <Alert className="mb-4 shrink-0">
          <AlertDescription>
            No observed dependencies yet — this view populates once span data is ingested.
          </AlertDescription>
        </Alert>
      )}

      {graph?.truncated && !bannerDismissed && (
        <Alert className="mb-4 shrink-0">
          <AlertDescription className="flex items-center justify-between gap-4">
            <span>Graph capped — not every service is drawn. Filter by team to see the rest.</span>
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
        <div className="grid min-h-[480px] flex-1 grid-cols-1 grid-rows-1 gap-4 lg:grid-cols-[1fr_380px]">
          <div ref={graphRef} className="h-full min-h-0 rounded-xl border border-border bg-card">
            <DependencyGraph
              graph={graph}
              selectedServiceId={selectedServiceId}
              onSelectNode={setSelectedServiceId}
              blastRadiusHighlight={blastRadiusHighlight}
              cycleMemberIds={cycleMemberIds}
              spofServiceIds={spofServiceIds}
            />
          </div>

          <div
            className={cn(
              'h-full min-h-0',
              selectedNode && 'max-lg:fixed max-lg:inset-x-2 max-lg:bottom-2 max-lg:z-20 max-lg:shadow-xl',
              selectedNode && (sheetExpanded ? 'max-lg:h-[80vh]' : 'max-lg:h-[42vh]'),
            )}
          >
            {selectedNode ? (
              <InspectorPanel
                key={selectedNode.serviceId}
                node={selectedNode}
                teamMap={teamMap}
                blastRadius={blastRadius}
                isBlastRadiusLoading={isBlastRadiusLoading}
                blastRadiusError={blastRadiusError}
                cycles={cycles}
                spofs={spofs}
                spofThreshold={spofsData?.threshold}
                spofRationale={spofsData?.rationale}
                nodesById={nodesById}
                onClose={() => setSelectedServiceId(null)}
                expanded={sheetExpanded}
                onToggleExpanded={() => setSheetExpanded((e) => !e)}
              />
            ) : (
              <Card className="h-full border-dashed">
                <CardContent className="flex h-full items-center justify-center py-8 text-center text-sm text-muted-foreground">
                  {selectionMissing
                    ? 'That service isn’t in this view — try All teams or Declared.'
                    : 'Select a service to see what breaks if it goes down.'}
                </CardContent>
              </Card>
            )}
          </div>
        </div>
      )}
      {selectedNode && <div className="h-[42vh] shrink-0 lg:hidden" aria-hidden="true" />}
    </AppLayout>
  )
}
