import { drag, type D3DragEvent } from 'd3-drag'
import {
  forceCenter,
  forceCollide,
  forceLink,
  forceManyBody,
  forceSimulation,
  type SimulationLinkDatum,
  type SimulationNodeDatum,
} from 'd3-force'
import { scaleOrdinal } from 'd3-scale'
import { select } from 'd3-selection'
import { zoom, zoomIdentity, type D3ZoomEvent } from 'd3-zoom'
import { useEffect, useMemo, useRef } from 'react'

import { normalizeHealth } from '#/lib/registry-types'

import type { Graph, GraphEdge, GraphNode } from '#/lib/topology-types'

interface SimNode extends SimulationNodeDatum, GraphNode {}
interface SimLink extends SimulationLinkDatum<SimNode> {
  protocol: GraphEdge['protocol']
  metadata: GraphEdge['metadata']
}

const healthColor = scaleOrdinal<string, string>()
  .domain(['healthy', 'degraded', 'down'])
  .range(['var(--color-success)', 'var(--color-warning)', 'var(--color-critical)'])

function endpointId(endpoint: SimLink['source']): string {
  return typeof endpoint === 'object' ? endpoint.serviceId : String(endpoint)
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

  const structureKey = useMemo(() => {
    const nodeIds = graph.nodes.map((node) => node.serviceId).sort().join(',')
    const edgeIds = graph.edges
      .map((edge) => `${edge.source}>${edge.target}`)
      .sort()
      .join(',')
    return `${nodeIds}|${edgeIds}`
  }, [graph.nodes, graph.edges])

  useEffect(() => {
    const svgEl = svgRef.current
    if (!svgEl) return

    const width = svgEl.clientWidth || 800
    const height = svgEl.clientHeight || 600

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
      .style('cursor', 'pointer')

    nodeSelection
      .append('circle')
      .attr('r', 14)
      .attr('fill', (node) => healthColor(normalizeHealth(node.healthStatus)))
      .attr('stroke', 'var(--background)')
      .attr('stroke-width', 2)

    nodeSelection
      .append('text')
      .text((node) => node.name)
      .attr('x', 18)
      .attr('y', 4)
      .attr('font-size', 11)
      .attr('fill', 'var(--foreground)')
      .attr('pointer-events', 'none')

    nodeSelection.on('click', (event, node) => {
      event.stopPropagation()
      onSelectNodeRef.current(node.serviceId)
    })
    svg.on('click', () => onSelectNodeRef.current(null))

    const zoomBehavior = zoom<SVGSVGElement, unknown>()
      .scaleExtent([0.2, 4])
      .on('zoom', (event: D3ZoomEvent<SVGSVGElement, unknown>) => {
        root.attr('transform', event.transform.toString())
      })
    svg.call(zoomBehavior).call(zoomBehavior.transform, zoomIdentity)

    const simulation = forceSimulation<SimNode>(nodes)
      .force('link', forceLink<SimNode, SimLink>(links).id((node) => node.serviceId).distance(90).strength(0.4))
      .force('charge', forceManyBody().strength(-220))
      .force('center', forceCenter(width / 2, height / 2))
      .force('collide', forceCollide(28))
      .on('tick', () => {
        linkSelection
          .selectAll<SVGLineElement, SimLink>('line')
          .attr('x1', (link) => (link.source as SimNode).x ?? 0)
          .attr('y1', (link) => (link.source as SimNode).y ?? 0)
          .attr('x2', (link) => (link.target as SimNode).x ?? 0)
          .attr('y2', (link) => (link.target as SimNode).y ?? 0)
        nodeSelection.attr('transform', (node) => `translate(${node.x ?? 0},${node.y ?? 0})`)
      })

    const dragBehavior = drag<SVGGElement, SimNode>()
      .on('start', (event: D3DragEvent<SVGGElement, SimNode, SimNode>, node) => {
        if (!event.active) simulation.alphaTarget(0.3).restart()
        node.fx = node.x
        node.fy = node.y
      })
      .on('drag', (event, node) => {
        node.fx = event.x
        node.fy = event.y
      })
      .on('end', (event) => {
        if (!event.active) simulation.alphaTarget(0)
      })
    nodeSelection.call(dragBehavior)

    return () => {
      simulation.stop()
      svg.selectAll('*').remove()
      svg.on('.zoom', null)
    }
    // Rebuilds the simulation/zoom/layout only when the set of nodes or edges actually
    // changes — a refetch that just updates node data (e.g. health status) must not reset
    // the user's pan/zoom/drag layout; see the sibling effect below for data-only refresh.
  }, [structureKey])

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
    nodeSelection.select<SVGCircleElement>('circle').attr('fill', (node) => healthColor(normalizeHealth(node.healthStatus)))
    nodeSelection.select<SVGTextElement>('text').text((node) => node.name)

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

  return <svg ref={svgRef} className="h-full w-full" role="img" aria-label="Service dependency graph" />
}
