import { render } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'

import { DependencyGraph, nodeAppearance, type SimNode } from '#/components/DependencyGraph'

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
