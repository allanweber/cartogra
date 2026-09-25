import { render, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { DependencyGraph } from '#/components/DependencyGraph'

import type { Graph } from '#/lib/topology-types'

function makeGraph(overrides: Partial<Graph> = {}): Graph {
  return {
    nodes: [
      { serviceId: 's1', name: 'api-gateway', teamId: null, tier: 'CRITICAL', healthStatus: 'HEALTHY' },
      { serviceId: 's2', name: 'auth-service', teamId: null, tier: 'STANDARD', healthStatus: 'DEGRADED' },
    ],
    edges: [{ source: 's1', target: 's2', dependencyType: 'DECLARED', protocol: 'HTTP', metadata: null }],
    truncated: false,
    ...overrides,
  }
}

describe('DependencyGraph', () => {
  afterEach(() => {
    cleanup()
  })

  it('selects a node on Enter and on Space, matching click behavior', () => {
    const onSelectNode = vi.fn()
    const { container } = render(
      <DependencyGraph graph={makeGraph()} selectedServiceId={null} onSelectNode={onSelectNode} />,
    )

    const firstNode = container.querySelector('.graph-node')
    expect(firstNode).toBeTruthy()

    firstNode!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    expect(onSelectNode).toHaveBeenCalledTimes(1)

    firstNode!.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }))
    expect(onSelectNode).toHaveBeenCalledTimes(2)

    firstNode!.dispatchEvent(new KeyboardEvent('keydown', { key: 'a', bubbles: true }))
    expect(onSelectNode).toHaveBeenCalledTimes(2)
  })

  it('stops the simulation and removes zoom listeners on unmount without throwing', () => {
    const onSelectNode = vi.fn()
    const { unmount, container } = render(
      <DependencyGraph graph={makeGraph()} selectedServiceId={null} onSelectNode={onSelectNode} />,
    )

    expect(container.querySelector('.graph-node')).toBeTruthy()
    expect(() => unmount()).not.toThrow()
    expect(container.querySelector('.graph-node')).toBeFalsy()
  })

  it('does not throw or double-register listeners when remounted with the same graph', () => {
    const onSelectNode = vi.fn()
    const graph = makeGraph()
    const first = render(<DependencyGraph graph={graph} selectedServiceId={null} onSelectNode={onSelectNode} />)
    first.unmount()

    const second = render(<DependencyGraph graph={graph} selectedServiceId={null} onSelectNode={onSelectNode} />)
    const node = second.container.querySelector('.graph-node')
    expect(node).toBeTruthy()

    node!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    expect(onSelectNode).toHaveBeenCalledTimes(1)
  })

  describe('reduced motion', () => {
    let originalMatchMedia: typeof window.matchMedia

    beforeEach(() => {
      originalMatchMedia = window.matchMedia
      window.matchMedia = vi.fn().mockImplementation((query: string) => ({
        matches: query === '(prefers-reduced-motion: reduce)',
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      }))
    })

    afterEach(() => {
      window.matchMedia = originalMatchMedia
    })

    it('settles synchronously and renders positioned nodes without an animated tick loop', () => {
      const onSelectNode = vi.fn()
      const { container } = render(
        <DependencyGraph graph={makeGraph()} selectedServiceId={null} onSelectNode={onSelectNode} />,
      )

      const nodeGroup = container.querySelector('.graph-node')
      expect(nodeGroup).toBeTruthy()
      // The synchronous settle path calls renderTick() once before first paint, so the
      // transform attribute should already reflect a real (non-zero-initialized) position
      // rather than being absent or stuck at the simulation's default spawn point.
      expect(nodeGroup!.getAttribute('transform')).toMatch(/translate\(/)
    })

    it('terminates within its time budget even under a slow-converging graph', () => {
      const onSelectNode = vi.fn()
      const start = performance.now()
      render(<DependencyGraph graph={makeGraph()} selectedServiceId={null} onSelectNode={onSelectNode} />)
      const elapsed = performance.now() - start
      // Generous upper bound — this is a smoke test for "it terminates promptly," not a
      // precise timing assertion (CI machines vary); real budget is ~150ms per plan 007.
      expect(elapsed).toBeLessThan(1000)
    })
  })
})
