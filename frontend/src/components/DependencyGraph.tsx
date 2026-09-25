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

export function DependencyGraph({
  graph,
  selectedServiceId,
  onSelectNode,
}: {
  graph: Graph
  selectedServiceId: string | null
  onSelectNode: (serviceId: string | null) => void
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
      if (!hasCenteredOnMountRef.current && selectedServiceId) centerOnService(selectedServiceId)
      hasCenteredOnMountRef.current = true
    } else {
      simulation.on('tick', renderTick)
      simulation.on('end', () => {
        if (!hasCenteredOnMountRef.current && selectedServiceId) centerOnService(selectedServiceId)
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

    if (!selectedServiceId) {
      svg.selectAll('.graph-node').style('opacity', 1)
      svg.selectAll('.graph-links line').style('opacity', 1)
      return
    }

    const neighborIds = new Set<string>([selectedServiceId])
    graph.edges.forEach((edge) => {
      if (edge.source === selectedServiceId) neighborIds.add(edge.target)
      if (edge.target === selectedServiceId) neighborIds.add(edge.source)
    })

    svg
      .selectAll<SVGGElement, SimNode>('.graph-node')
      .style('opacity', (node) => (neighborIds.has(node.serviceId) ? 1 : 0.15))
    svg
      .selectAll<SVGLineElement, SimLink>('.graph-links line')
      .style('opacity', (link) =>
        endpointId(link.source) === selectedServiceId || endpointId(link.target) === selectedServiceId ? 1 : 0.1,
      )
  }, [selectedServiceId, graph])

  return (
    <svg
      ref={svgRef}
      className="h-full w-full"
      role="group"
      aria-label="Service dependency graph. Tab to move between services, Enter or Space to select one."
    />
  )
}
