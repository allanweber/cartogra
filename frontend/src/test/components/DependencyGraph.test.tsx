import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  DependencyGraph,
  highlightRingAppearance,
  linkOpacity,
  nodeAppearance,
  nodeOpacity,
  type BlastRadiusHighlightMap,
  type SimNode,
} from '#/components/DependencyGraph'

import type { Graph } from '#/lib/topology-types'

function node(overrides: Partial<SimNode> = {}): SimNode {
  return {
    serviceId: 's1',
    name: 'payments',
    teamId: null,
    tier: null,
    healthStatus: 'HEALTHY',
    ...overrides,
  }
}

describe('nodeAppearance', () => {
  it('describes a healthy node with no tier', () => {
    const appearance = nodeAppearance(node())
    expect(appearance.ariaLabel).toBe('payments, healthy')
    expect(appearance.fill).toBe('var(--color-success)')
    expect(appearance.strokeWidth).toBe(2)
    expect(appearance.strokeDasharray).toBeNull()
    expect(appearance.glyph).toBe('')
    expect(appearance.label).toBe('payments')
  })

  it('appends the lowercased tier to the label when present', () => {
    const appearance = nodeAppearance(node({ tier: 'CRITICAL' }))
    expect(appearance.ariaLabel).toBe('payments, healthy, critical tier')
  })

  it('marks a degraded node with a dashed stroke but no glyph', () => {
    const appearance = nodeAppearance(node({ healthStatus: 'DEGRADED' }))
    expect(appearance.ariaLabel).toBe('payments, degraded')
    expect(appearance.fill).toBe('var(--color-warning)')
    expect(appearance.strokeWidth).toBe(2)
    expect(appearance.strokeDasharray).toBe('3,2')
    expect(appearance.glyph).toBe('')
  })

  it('marks a down node with a thicker stroke and an exclamation glyph', () => {
    const appearance = nodeAppearance(node({ healthStatus: 'UNHEALTHY', tier: 'STANDARD' }))
    expect(appearance.ariaLabel).toBe('payments, down, standard tier')
    expect(appearance.fill).toBe('var(--color-critical)')
    expect(appearance.strokeWidth).toBe(3)
    expect(appearance.strokeDasharray).toBeNull()
    expect(appearance.glyph).toBe('!')
  })
})

describe('highlightRingAppearance', () => {
  const map: BlastRadiusHighlightMap = new Map([
    ['s2', 'upstream'],
    ['s3', 'downstream'],
  ])

  it('gives the selected node the ring color regardless of the highlight map', () => {
    expect(highlightRingAppearance('s1', 's1', map)).toEqual({ stroke: 'var(--ring)', strokeWidth: 3 })
  })

  it('gives an upstream node the upstream color', () => {
    expect(highlightRingAppearance('s2', 's1', map)).toEqual({
      stroke: 'var(--color-blast-upstream)',
      strokeWidth: 2.5,
    })
  })

  it('gives a downstream node the downstream color', () => {
    expect(highlightRingAppearance('s3', 's1', map)).toEqual({
      stroke: 'var(--color-blast-downstream)',
      strokeWidth: 2.5,
    })
  })

  it('returns null for a node in neither the map nor selected', () => {
    expect(highlightRingAppearance('s4', 's1', map)).toBeNull()
  })

  it('returns null when the map is null (loading or no selection)', () => {
    expect(highlightRingAppearance('s2', 's1', null)).toBeNull()
  })
})

describe('nodeOpacity', () => {
  const map: BlastRadiusHighlightMap = new Map([['s2', 'upstream']])

  it('is full opacity when nothing is selected', () => {
    expect(nodeOpacity('s5', null, map)).toBe(1)
  })

  it('is full opacity while the highlight map is still loading (null)', () => {
    expect(nodeOpacity('s5', 's1', null)).toBe(1)
  })

  it('is full opacity for the selected node', () => {
    expect(nodeOpacity('s1', 's1', map)).toBe(1)
  })

  it('is full opacity for a node in the highlight map', () => {
    expect(nodeOpacity('s2', 's1', map)).toBe(1)
  })

  it('dims a node outside the selection and the highlight map', () => {
    expect(nodeOpacity('s9', 's1', map)).toBe(0.15)
  })
})

describe('linkOpacity', () => {
  const map: BlastRadiusHighlightMap = new Map([['s2', 'upstream']])

  it('is full opacity when nothing is selected', () => {
    expect(linkOpacity('s9', 's8', null, map)).toBe(1)
  })

  it('is full opacity while the highlight map is still loading (null)', () => {
    expect(linkOpacity('s9', 's8', 's1', null)).toBe(1)
  })

  it('is full opacity when either endpoint is the selected node', () => {
    expect(linkOpacity('s1', 's9', 's1', map)).toBe(1)
  })

  it('is full opacity when either endpoint is in the highlight map', () => {
    expect(linkOpacity('s2', 's9', 's1', map)).toBe(1)
  })

  it('dims a link with neither endpoint involved', () => {
    expect(linkOpacity('s8', 's9', 's1', map)).toBe(0.1)
  })
})

function graph(): Graph {
  return {
    nodes: [
      { serviceId: 's1', name: 'api-gateway', teamId: null, tier: null, healthStatus: 'HEALTHY' },
      { serviceId: 's2', name: 'auth-service', teamId: null, tier: null, healthStatus: 'HEALTHY' },
    ],
    edges: [{ source: 's1', target: 's2', dependencyType: 'DECLARED', protocol: 'HTTP', metadata: null }],
    truncated: false,
  }
}

describe('DependencyGraph camera pan', () => {
  // Forces the synchronous reduced-motion settle path so the force simulation finishes
  // (and centerOnService can act on real positions) within the render itself, instead of
  // needing to wait on d3's async tick loop.
  beforeEach(() => {
    window.matchMedia = (query: string) => ({
      matches: true,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    })
  })

  it('pans when selectedServiceId is set externally (a deep link), not by a click', () => {
    const { container, rerender } = render(
      <DependencyGraph graph={graph()} selectedServiceId={null} onSelectNode={() => {}} />,
    )
    const root = container.querySelector('.graph-root')!
    const initialTransform = root.getAttribute('transform')

    rerender(<DependencyGraph graph={graph()} selectedServiceId="s2" onSelectNode={() => {}} />)

    expect(root.getAttribute('transform')).not.toBe(initialTransform)
  })

  it('does not pan when selectedServiceId only echoes back a self-emitted click', () => {
    let selected: string | null = null
    const handleSelect = (id: string | null) => {
      selected = id
    }

    const { container, rerender } = render(
      <DependencyGraph graph={graph()} selectedServiceId={null} onSelectNode={handleSelect} />,
    )
    const root = container.querySelector('.graph-root')!

    const nodeCircle = container.querySelector('.graph-node circle')!
    const nodeGroup = nodeCircle.parentElement as unknown as SVGGElement
    nodeGroup.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    expect(selected).toBe('s1')

    const transformAfterClick = root.getAttribute('transform')
    rerender(<DependencyGraph graph={graph()} selectedServiceId={selected} onSelectNode={handleSelect} />)

    expect(root.getAttribute('transform')).toBe(transformAfterClick)
  })
})
