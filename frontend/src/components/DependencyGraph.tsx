import { drag, type D3DragEvent } from 'd3-drag'
import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type Simulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from 'd3-force'
import { scaleOrdinal } from 'd3-scale'
import { select, type BaseType, type Selection } from 'd3-selection'
import { zoom, zoomIdentity, type D3ZoomEvent, type ZoomBehavior } from 'd3-zoom'
import { useEffect, useMemo, useRef } from 'react'

import { useReducedMotion } from '#/hooks/useReducedMotion'
import { normalizeHealth } from '#/lib/registry-types'

import type { Graph, GraphEdge, GraphNode } from '#/lib/topology-types'
import type { ServiceHealth } from '#/lib/registry-types'

export interface SimNode extends SimulationNodeDatum, GraphNode {}
interface SimLink extends SimulationLinkDatum<SimNode> {
  protocol: GraphEdge['protocol']
  metadata: GraphEdge['metadata']
}

const healthColor = scaleOrdinal<string, string>()
  .domain(['healthy', 'degraded', 'down'])
  .range(['var(--color-success)', 'var(--color-warning)', 'var(--color-critical)'])

// Health is never signaled by fill color alone: degraded nodes get a dashed outline
// and down nodes get an exclamation glyph, so the state also reads for color-blind users.
function healthDasharray(health: ServiceHealth): string | null {
  return health === 'degraded' ? '3,2' : null
}

// Settle instantly instead of animating — D3's tick loop is JS-driven, so the global
// `prefers-reduced-motion` CSS rule in styles.css can't reach it; this can. Ticks run
// until the simulation naturally converges (alpha < alphaMin, d3-force's own stopping
// condition — matches what the animated path would do on its own), bounded by a wall-clock
// budget so a very large graph degrades to a slightly less-settled layout instead of
// blocking the main thread indefinitely.
const REDUCED_MOTION_SETTLE_BUDGET_MS = 150

function endpointId(endpoint: SimLink['source']): string {
  return typeof endpoint === 'object' ? endpoint.serviceId : String(endpoint)
}

export interface NodeAppearance {
  ariaLabel: string
  fill: string
  strokeWidth: number
  strokeDasharray: string | null
  glyph: string
  label: string
}

// The one place a node's health+tier become what a caller (visual or assistive-tech)
// perceives. Both the initial-build and data-refresh effects call this — never derive
// aria-label/fill/glyph independently, or the two fall out of sync on refresh.
export function nodeAppearance(node: SimNode): NodeAppearance {
  const health = normalizeHealth(node.healthStatus)
  return {
    ariaLabel: `${node.name}, ${health}${node.tier ? `, ${node.tier.toLowerCase()} tier` : ''}`,
    fill: healthColor(health),
    strokeWidth: health === 'down' ? 3 : 2,
    strokeDasharray: healthDasharray(health),
    glyph: health === 'down' ? '!' : '',
    label: node.name,
  }
}

function applyNodeAppearance<TParent extends BaseType>(
  nodeSelection: Selection<SVGGElement, SimNode, TParent, unknown>,
) {
  nodeSelection.attr('aria-label', (node) => nodeAppearance(node).ariaLabel)
  nodeSelection
    .select<SVGCircleElement>('circle.graph-node-visible')
    .attr('fill', (node) => nodeAppearance(node).fill)
    .attr('stroke-width', (node) => nodeAppearance(node).strokeWidth)
    .attr('stroke-dasharray', (node) => nodeAppearance(node).strokeDasharray)
  nodeSelection.select<SVGTextElement>('text.graph-node-health-glyph').text((node) => nodeAppearance(node).glyph)
  nodeSelection.select<SVGTextElement>('text.graph-node-label').text((node) => nodeAppearance(node).label)
}

export type BlastRadiusHighlightMap = Map<string, 'upstream' | 'downstream'>

// The one place a node's blast-radius ring color is decided — layered outside the health
// circle (see the `graph-node-highlight-ring` element below) so health and blast-radius
// encodings never compete for the same attribute the way a single fill color would.
export function highlightRingAppearance(
  serviceId: string,
  selectedServiceId: string | null,
  highlightMap: BlastRadiusHighlightMap | null,
): { stroke: string; strokeWidth: number; strokeDasharray: string } | null {
  if (serviceId === selectedServiceId) return { stroke: 'var(--ring)', strokeWidth: 3, strokeDasharray: 'none' }
  const kind = highlightMap?.get(serviceId)
  if (kind === 'upstream') return { stroke: 'var(--color-blast-upstream)', strokeWidth: 2.5, strokeDasharray: '4 3' }
  if (kind === 'downstream') return { stroke: 'var(--color-blast-downstream)', strokeWidth: 2.5, strokeDasharray: 'none' }
  return null
}

// No dimming until blast-radius data is available (highlightMap is null while the query is
// loading or nothing is selected) — avoids flashing a stale/incomplete highlight. The
// selected-node ring still shows instantly on click regardless, since it only depends on
// selectedServiceId, not the map — see highlightRingAppearance above.
export function nodeOpacity(
  serviceId: string,
  selectedServiceId: string | null,
  highlightMap: BlastRadiusHighlightMap | null,
): number {
  if (!selectedServiceId || !highlightMap) return 1
  return serviceId === selectedServiceId || highlightMap.has(serviceId) ? 1 : 0.25
}

export function linkOpacity(
  sourceId: string,
  targetId: string,
  selectedServiceId: string | null,
  highlightMap: BlastRadiusHighlightMap | null,
): number {
  if (!selectedServiceId || !highlightMap) return 1
  const involved = (id: string) => id === selectedServiceId || highlightMap.has(id)
  return involved(sourceId) || involved(targetId) ? 1 : 0.1
}

// A cross-cutting fact about a node (like blast-radius membership above), not part of its own
// identity — driven into the canvas by the same non-structural effect that handles selection
// and blast-radius highlighting, never the structural rebuild, so a cycle query that resolves
// after the graph is already mounted still lights up the right nodes.
export function isCycleMember(serviceId: string, cycleMemberIds: Set<string> | null): boolean {
  return cycleMemberIds?.has(serviceId) ?? false
}

// Same cross-cutting-fact treatment as isCycleMember above, for SPOF status.
export function isSpof(serviceId: string, spofServiceIds: Set<string> | null): boolean {
  return spofServiceIds?.has(serviceId) ?? false
}

export function DependencyGraph({
  graph,
  selectedServiceId,
  onSelectNode,
  blastRadiusHighlight,
  blastRadiusPartial,
  cycleMemberIds,
  spofServiceIds,
}: {
  graph: Graph
  selectedServiceId: string | null
  onSelectNode: (serviceId: string | null) => void
  blastRadiusHighlight?: BlastRadiusHighlightMap | null
  // Truncated blast radius: an unlisted node may still be affected, so never dim it as "unaffected".
  blastRadiusPartial?: boolean
  cycleMemberIds?: Set<string> | null
  spofServiceIds?: Set<string> | null
}) {
  const svgRef = useRef<SVGSVGElement>(null)
  const onSelectNodeRef = useRef(onSelectNode)
  onSelectNodeRef.current = onSelectNode
  const zoomBehaviorRef = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null)
  const reducedMotion = useReducedMotion()
  const reducedMotionRef = useRef(reducedMotion)
  reducedMotionRef.current = reducedMotion

  // What this component itself last told its caller was selected, via onSelectNode — an
  // incoming selectedServiceId that doesn't match came from outside (a deep link, a link
  // from another screen), which is the only case that should pan/zoom the canvas; a value
  // that matches means the user just clicked a node here, and re-centering on every click
  // would be disorienting motion for no reason during ordinary exploration.
  const lastEmittedSelectionRef = useRef<string | null>(null)
  // Pans to an initial selection once, on this component's true first mount — never again
  // on a later structural rebuild (a filter/team/type change) alone, even if the selection
  // that triggered it was never "claimed" by a click (see lastEmittedSelectionRef above).
  const hasCenteredOnMountRef = useRef(false)

  const structureKey = useMemo(() => {
    const nodeIds = graph.nodes.map((node) => node.serviceId).sort().join(',')
    const edgeIds = graph.edges
      .map((edge) => `${edge.source}>${edge.target}`)
      .sort()
      .join(',')
    return `${nodeIds}|${edgeIds}`
  }, [graph.nodes, graph.edges])

  // Snaps the viewport to a node instantly rather than animating the pan/zoom — d3-transition
  // isn't a dependency here, and an instant cut also sidesteps the reduced-motion question
  // entirely rather than needing to gate an eased transition.
  function centerOnService(serviceId: string) {
    const svgEl = svgRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!svgEl || !zoomBehavior) return
    const svg = select(svgEl)
    const width = svgEl.clientWidth || 800
    const height = svgEl.clientHeight || 600

    let target: SimNode | undefined
    svg.selectAll<SVGGElement, SimNode>('.graph-node').each(function (node) {
      if (node.serviceId === serviceId) target = node
    })
    if (!target || target.x == null || target.y == null) return

    const scale = 1.4
    const transform = zoomIdentity
      .translate(width / 2, height / 2)
      .scale(scale)
      .translate(-target.x, -target.y)

    svg.call(zoomBehavior.transform, transform)
  }

  // Zooms out (never in) so every node plus its label fits the viewport — without this a
  // narrow canvas (phone width) opens with part of the layout outside the visible area.
  function fitToView() {
    const svgEl = svgRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!svgEl || !zoomBehavior) return
    const width = svgEl.clientWidth || 800
    const height = svgEl.clientHeight || 600
    const labelAllowance = 240
    let minX = Infinity
    let maxX = -Infinity
    let minY = Infinity
    let maxY = -Infinity
    select(svgEl)
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .each((node) => {
        if (node.x == null || node.y == null) return
        minX = Math.min(minX, node.x - 20)
        maxX = Math.max(maxX, node.x + 20 + labelAllowance)
        minY = Math.min(minY, node.y - 20)
        maxY = Math.max(maxY, node.y + 20)
      })
    if (!Number.isFinite(minX)) return
    const boundsWidth = maxX - minX
    const boundsHeight = maxY - minY
    const scale = Math.min(1, Math.max(0.4, Math.min(width / boundsWidth, height / boundsHeight)))
    const transform = zoomIdentity
      .translate(width / 2, height / 2)
      .scale(scale)
      .translate(-(minX + boundsWidth / 2), -(minY + boundsHeight / 2))
    select(svgEl).call(zoomBehavior.transform, transform)
  }

  useEffect(() => {
    const svgEl = svgRef.current
    if (!svgEl) return

    const width = svgEl.clientWidth || 800
    const height = svgEl.clientHeight || 600
    const reduceMotion = reducedMotionRef.current

    const nodes: SimNode[] = graph.nodes.map((node) => ({ ...node }))
    const nodesById = new Map(nodes.map((node) => [node.serviceId, node]))
    const links: SimLink[] = graph.edges
      .filter((edge) => nodesById.has(edge.source) && nodesById.has(edge.target))
      .map((edge) => ({ source: edge.source, target: edge.target, protocol: edge.protocol, metadata: edge.metadata }))

    const svg = select(svgEl)
    svg.selectAll('*').remove()

    const root = svg.append('g').attr('class', 'graph-root')
    const linkSelection = root
      .append('g')
      .attr('class', 'graph-links')
      .selectAll<SVGGElement, SimLink>('g')
      .data(links)
      .join('g')
      .attr('class', 'graph-link')

    linkSelection
      .append('title')
      .text((link) => (link.metadata ? `${link.protocol} — ${link.metadata}` : link.protocol))

    linkSelection.append('line').attr('stroke', 'transparent').attr('stroke-width', 10).style('cursor', 'pointer')

    linkSelection
      .append('line')
      .attr('stroke', 'var(--border)')
      .attr('stroke-width', 1.5)
      .attr('pointer-events', 'none')

    const nodeSelection = root
      .append('g')
      .attr('class', 'graph-nodes')
      .selectAll<SVGGElement, SimNode>('g')
      .data(nodes, (node) => node.serviceId)
      .join('g')
      .attr('class', 'graph-node')
      .attr('tabindex', 0)
      .attr('role', 'button')
      .style('cursor', 'pointer')

    // Oversized transparent hit circle keeps the visible glyph at r=14 while giving
    // pointer and touch input a 44px target, matching the link hit-line pattern below.
    nodeSelection.append('circle').attr('class', 'graph-node-hit').attr('r', 22).attr('fill', 'transparent')

    nodeSelection.append('circle').attr('class', 'graph-node-visible').attr('r', 14).attr('stroke', 'var(--background)')

    // Painted after graph-node-visible so it rings outside the health circle rather than
    // competing with it — see highlightRingAppearance for the selected/upstream/downstream
    // color decision applied to this element in the opacity/highlight effect below.
    nodeSelection
      .append('circle')
      .attr('class', 'graph-node-highlight-ring')
      .attr('r', 19)
      .attr('fill', 'none')
      .attr('stroke-width', 0)
      .attr('pointer-events', 'none')

    // Cycle-membership glyph, a distinct symbol (not just a color) at the node's top-right —
    // toggled by opacity in the selection/highlight effect below, same as the ring above.
    nodeSelection
      .append('text')
      .attr('class', 'graph-node-cycle-badge')
      .attr('x', 11)
      .attr('y', -9)
      .attr('text-anchor', 'middle')
      .attr('font-size', 10)
      .attr('font-weight', 700)
      .attr('fill', 'var(--color-critical)')
      .attr('pointer-events', 'none')
      .attr('opacity', 0)
      .text('↻')

    // SPOF-status glyph, top-left (mirrors the cycle badge's top-right placement) so the two
    // standing-risk markers never overlap — a "standing property of the node, like health"
    // per the design brief, not the same visual channel as the cycle highlight above.
    nodeSelection
      .append('text')
      .attr('class', 'graph-node-spof-badge')
      .attr('x', -11)
      .attr('y', -9)
      .attr('text-anchor', 'middle')
      .attr('font-size', 10)
      .attr('font-weight', 700)
      .attr('fill', 'var(--color-warning)')
      .attr('pointer-events', 'none')
      .attr('opacity', 0)
      .text('▲')

    // Non-color marker for the down state, in addition to the dashed outline for degraded below.
    nodeSelection
      .append('text')
      .attr('class', 'graph-node-health-glyph')
      .attr('x', 0)
      .attr('y', 4)
      .attr('text-anchor', 'middle')
      .attr('font-size', 11)
      .attr('font-weight', 700)
      .attr('fill', 'var(--background)')
      .attr('pointer-events', 'none')

    nodeSelection
      .append('text')
      .attr('class', 'graph-node-label')
      .attr('x', 18)
      .attr('y', 4)
      .attr('font-size', 11)
      .attr('fill', 'var(--foreground)')
      .attr('pointer-events', 'none')

    applyNodeAppearance(nodeSelection)

    function selectFromEvent(event: Event, node: SimNode) {
      event.stopPropagation()
      lastEmittedSelectionRef.current = node.serviceId
      onSelectNodeRef.current(node.serviceId)
    }
    nodeSelection.on('click', selectFromEvent)
    nodeSelection.on('keydown', (event: KeyboardEvent, node) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        selectFromEvent(event, node)
      }
    })
    svg.on('click', () => {
      lastEmittedSelectionRef.current = null
      onSelectNodeRef.current(null)
    })

    const zoomBehavior = zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.2, 4])
      .on('zoom', (event: D3ZoomEvent<SVGSVGElement, unknown>) => {
        root.attr('transform', event.transform.toString())
      })
    svg.call(zoomBehavior).call(zoomBehavior.transform, zoomIdentity)
    zoomBehaviorRef.current = zoomBehavior

    function renderTick() {
      linkSelection
        .selectAll<SVGLineElement, SimLink>('line')
        .attr('x1', (link) => (link.source as SimNode).x ?? 0)
        .attr('y1', (link) => (link.source as SimNode).y ?? 0)
        .attr('x2', (link) => (link.target as SimNode).x ?? 0)
        .attr('y2', (link) => (link.target as SimNode).y ?? 0)
      nodeSelection.attr('transform', (node) => `translate(${node.x ?? 0},${node.y ?? 0})`)
    }

    const simulation: Simulation<SimNode, SimLink> = forceSimulation<SimNode>(nodes)
      .force('link', forceLink<SimNode, SimLink>(links).id((node) => node.serviceId).distance(90).strength(0.4))
      .force('charge', forceManyBody().strength(-220))
      .force('center', forceCenter(width / 2, height / 2))
      .force('collide', forceCollide(28))

    if (reduceMotion) {
      simulation.stop()
      const deadline = performance.now() + REDUCED_MOTION_SETTLE_BUDGET_MS
      while (simulation.alpha() > simulation.alphaMin() && performance.now() < deadline) {
        simulation.tick()
      }
      renderTick()
      if (!hasCenteredOnMountRef.current) {
        if (selectedServiceId) centerOnService(selectedServiceId)
        else fitToView()
      }
      hasCenteredOnMountRef.current = true
    } else {
      simulation.on('tick', renderTick)
      simulation.on('end', () => {
        if (!hasCenteredOnMountRef.current) {
          if (selectedServiceId) centerOnService(selectedServiceId)
          else fitToView()
        }
        hasCenteredOnMountRef.current = true
      })
    }

    const dragBehavior = drag<SVGGElement, SimNode>()
      .clickDistance(4)
      .on('start', (event: D3DragEvent<SVGGElement, SimNode, SimNode>, node) => {
        if (!reducedMotionRef.current && !event.active) simulation.alphaTarget(0.3).restart()
        node.fx = node.x
        node.fy = node.y
      })
      .on('drag', (event, node) => {
        node.fx = event.x
        node.fy = event.y
        if (reducedMotionRef.current) renderTick()
      })
      .on('end', (event) => {
        if (!reducedMotionRef.current && !event.active) simulation.alphaTarget(0)
      })
    nodeSelection.call(dragBehavior)

    return () => {
      simulation.stop()
      svg.selectAll('*').remove()
      svg.on('.zoom', null)
      zoomBehaviorRef.current = null
    }
    // Rebuilds the simulation/zoom/layout only when the set of nodes or edges actually
    // changes — a refetch that just updates node data (e.g. health status) must not reset
    // the user's pan/zoom/drag layout; see the sibling effect below for data-only refresh.
  }, [structureKey])

  // Re-centers on a newly requested external selection without rebuilding the simulation —
  // covers navigating here from a different service's Dependencies tab while the graph
  // (same node/edge set) is already mounted. Skipped when selectedServiceId just echoes
  // back what this component itself last emitted (an ordinary click) — see
  // lastEmittedSelectionRef above.
  useEffect(() => {
    if (!selectedServiceId) return
    if (selectedServiceId === lastEmittedSelectionRef.current) return
    centerOnService(selectedServiceId)
  }, [selectedServiceId])

  useEffect(() => {
    const svgEl = svgRef.current
    if (!svgEl) return
    const svg = select(svgEl)

    const latestById = new Map(graph.nodes.map((node) => [node.serviceId, node]))
    const nodeSelection = svg.selectAll<SVGGElement, SimNode>('.graph-node')
    nodeSelection.each(function (node) {
      const latest = latestById.get(node.serviceId)
      if (!latest) return
      Object.assign(node, latest)
    })
    applyNodeAppearance(nodeSelection)

    const latestEdgeByKey = new Map(graph.edges.map((edge) => [`${edge.source}>${edge.target}`, edge]))
    svg.selectAll<SVGGElement, SimLink>('.graph-link').each(function (link) {
      const latest = latestEdgeByKey.get(`${endpointId(link.source)}>${endpointId(link.target)}`)
      if (!latest) return
      link.protocol = latest.protocol
      link.metadata = latest.metadata
    })
    svg
      .selectAll<SVGGElement, SimLink>('.graph-link')
      .select('title')
      .text((link) => (link.metadata ? `${link.protocol} — ${link.metadata}` : link.protocol))
  }, [graph])

  useEffect(() => {
    const svgEl = svgRef.current
    if (!svgEl) return
    const svg = select(svgEl)
    const highlightMap = blastRadiusHighlight ?? null
    const dimMap = blastRadiusPartial ? null : highlightMap
    const cycleMembers = cycleMemberIds ?? null
    const spofs = spofServiceIds ?? null

    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .style('opacity', (node) => nodeOpacity(node.serviceId, selectedServiceId, dimMap))
    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .select<SVGCircleElement>('circle.graph-node-highlight-ring')
      .attr('stroke', (node) => highlightRingAppearance(node.serviceId, selectedServiceId, highlightMap)?.stroke ?? 'none')
      .attr(
        'stroke-width',
        (node) => highlightRingAppearance(node.serviceId, selectedServiceId, highlightMap)?.strokeWidth ?? 0,
      )
      .attr(
        'stroke-dasharray',
        (node) => highlightRingAppearance(node.serviceId, selectedServiceId, highlightMap)?.strokeDasharray ?? 'none',
      )
    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .select<SVGTextElement>('text.graph-node-cycle-badge')
      .attr('opacity', (node) => (isCycleMember(node.serviceId, cycleMembers) ? 1 : 0))
    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .select<SVGTextElement>('text.graph-node-spof-badge')
      .attr('opacity', (node) => (isSpof(node.serviceId, spofs) ? 1 : 0))
    // Recomputed here rather than in applyNodeAppearance so a cycle/SPOF query resolving after
    // the graph is already mounted still updates the label — nodeAppearance() remains the
    // single source of the health/tier portion, this only appends these facts on top of it.
    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .attr('aria-label', (node) => {
        let label = nodeAppearance(node).ariaLabel
        if (selectedServiceId && node.serviceId !== selectedServiceId) {
          const kind = highlightMap?.get(node.serviceId)
          if (kind) label += `, ${kind} of the selected service`
        }
        if (isCycleMember(node.serviceId, cycleMembers)) label += ', part of a dependency cycle'
        if (isSpof(node.serviceId, spofs)) label += ', a single point of failure'
        return label
      })
    svg
      .selectAll<SVGLineElement, SimLink>('.graph-links line')
      .style('opacity', (link) => linkOpacity(endpointId(link.source), endpointId(link.target), selectedServiceId, dimMap))
  }, [selectedServiceId, blastRadiusHighlight, blastRadiusPartial, cycleMemberIds, spofServiceIds, graph])

  return (
    <svg
      ref={svgRef}
      className="h-full w-full"
      role="group"
      aria-label="Service dependency graph. Tab to move between services, Enter or Space to select one."
    />
  )
}
