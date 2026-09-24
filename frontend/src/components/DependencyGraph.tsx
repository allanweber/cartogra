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
import { select } from 'd3-selection'
import { zoom, zoomIdentity, type D3ZoomEvent, type ZoomBehavior } from 'd3-zoom'
import { useEffect, useMemo, useRef } from 'react'

import { useReducedMotion } from '#/hooks/useReducedMotion'
import { normalizeHealth } from '#/lib/registry-types'

import type { Graph, GraphEdge, GraphNode } from '#/lib/topology-types'
import type { ServiceHealth } from '#/lib/registry-types'

interface SimNode extends SimulationNodeDatum, GraphNode {}
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
// `prefers-reduced-motion` CSS rule in styles.css can't reach it; this can.
const REDUCED_MOTION_SETTLE_TICKS = 300

function endpointId(endpoint: SimLink['source']): string {
  return typeof endpoint === 'object' ? endpoint.serviceId : String(endpoint)
}

export function DependencyGraph({
  graph,
  selectedServiceId,
  onSelectNode,
  focusServiceId,
}: {
  graph: Graph
  selectedServiceId: string | null
  onSelectNode: (serviceId: string | null) => void
  /** A service to pan/zoom to center on once the layout is available (e.g. arriving via a deep link). */
  focusServiceId?: string | null
}) {
  const svgRef = useRef<SVGSVGElement>(null)
  const onSelectNodeRef = useRef(onSelectNode)
  onSelectNodeRef.current = onSelectNode
  const zoomBehaviorRef = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null)
  const reducedMotion = useReducedMotion()
  const reducedMotionRef = useRef(reducedMotion)
  reducedMotionRef.current = reducedMotion

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
      .attr(
        'aria-label',
        (node) =>
          `${node.name}, ${normalizeHealth(node.healthStatus)}${node.tier ? `, ${node.tier.toLowerCase()} tier` : ''}`,
      )
      .style('cursor', 'pointer')

    // Oversized transparent hit circle keeps the visible glyph at r=14 while giving
    // pointer and touch input a 44px target, matching the link hit-line pattern below.
    nodeSelection.append('circle').attr('class', 'graph-node-hit').attr('r', 22).attr('fill', 'transparent')

    nodeSelection
      .append('circle')
      .attr('class', 'graph-node-visible')
      .attr('r', 14)
      .attr('fill', (node) => healthColor(normalizeHealth(node.healthStatus)))
      .attr('stroke', 'var(--background)')
      .attr('stroke-width', (node) => (normalizeHealth(node.healthStatus) === 'down' ? 3 : 2))
      .attr('stroke-dasharray', (node) => healthDasharray(normalizeHealth(node.healthStatus)))

    // Non-color marker for the down state, in addition to the dashed outline for degraded above.
    nodeSelection
      .append('text')
      .attr('class', 'graph-node-health-glyph')
      .text((node) => (normalizeHealth(node.healthStatus) === 'down' ? '!' : ''))
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
      .text((node) => node.name)
      .attr('x', 18)
      .attr('y', 4)
      .attr('font-size', 11)
      .attr('fill', 'var(--foreground)')
      .attr('pointer-events', 'none')

    function selectFromEvent(event: Event, node: SimNode) {
      event.stopPropagation()
      onSelectNodeRef.current(node.serviceId)
    }
    nodeSelection.on('click', selectFromEvent)
    nodeSelection.on('keydown', (event: KeyboardEvent, node) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        selectFromEvent(event, node)
      }
    })
    svg.on('click', () => onSelectNodeRef.current(null))

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
      for (let i = 0; i < REDUCED_MOTION_SETTLE_TICKS; i++) simulation.tick()
      renderTick()
      if (focusServiceId) centerOnService(focusServiceId)
    } else {
      simulation.on('tick', renderTick)
      simulation.on('end', () => {
        if (focusServiceId) centerOnService(focusServiceId)
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

  // Re-centers on a newly requested focus target without rebuilding the simulation —
  // covers navigating here from a different service's Dependencies tab while the graph
  // (same node/edge set) is already mounted.
  useEffect(() => {
    if (!focusServiceId) return
    centerOnService(focusServiceId)
  }, [focusServiceId])

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
    nodeSelection
      .attr(
        'aria-label',
        (node) =>
          `${node.name}, ${normalizeHealth(node.healthStatus)}${node.tier ? `, ${node.tier.toLowerCase()} tier` : ''}`,
      )
    nodeSelection
      .select<SVGCircleElement>('circle.graph-node-visible')
      .attr('fill', (node) => healthColor(normalizeHealth(node.healthStatus)))
      .attr('stroke-width', (node) => (normalizeHealth(node.healthStatus) === 'down' ? 3 : 2))
      .attr('stroke-dasharray', (node) => healthDasharray(normalizeHealth(node.healthStatus)))
    nodeSelection
      .select<SVGTextElement>('text.graph-node-health-glyph')
      .text((node) => (normalizeHealth(node.healthStatus) === 'down' ? '!' : ''))
    nodeSelection.select<SVGTextElement>('text.graph-node-label').text((node) => node.name)

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
