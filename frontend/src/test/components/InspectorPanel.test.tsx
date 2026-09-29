import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

import { InspectorPanel } from '#/components/InspectorPanel'
import { ApiError } from '#/lib/api'

import type { BlastRadius, GraphNode } from '#/lib/topology-types'

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  Link: ({
    children,
    to,
    params,
  }: {
    children: React.ReactNode
    to: string
    params?: Record<string, string>
  }) => {
    const href = params ? to.replace(/\$(\w+)/g, (_, key: string) => params[key] ?? '') : to
    return <a href={href}>{children}</a>
  },
}))

function node(overrides: Partial<GraphNode> = {}): GraphNode {
  return {
    serviceId: 's1',
    name: 'payments-api',
    teamId: 't1',
    tier: 'CRITICAL',
    healthStatus: 'HEALTHY',
    ...overrides,
  }
}

function blastRadius(overrides: Partial<BlastRadius> = {}): BlastRadius {
  return {
    serviceId: 's1',
    upstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
    downstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: false, nodesBeyondCap: 0 },
    maxDepth: 3,
    ...overrides,
  }
}

const teamMap = new Map([['t1', 'Payments Team']])

describe('InspectorPanel', () => {
  it('defaults to the Blast Radius tab on first render', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius()}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    expect(screen.getByRole('tab', { name: 'Blast Radius' })).toHaveAttribute('data-state', 'active')
    expect(screen.getByRole('tab', { name: 'Details' })).toHaveAttribute('data-state', 'inactive')
  })

  it('switches to Details on click and shows team/tier/health', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius()}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Details' }))
    expect(screen.getByText('Payments Team')).toBeInTheDocument()
    expect(screen.getByText('critical')).toBeInTheDocument()
    expect(screen.getByText('healthy')).toBeInTheDocument()
  })

  it('shows "Unassigned" on the Details tab when the node has no team', () => {
    render(
      <InspectorPanel
        node={node({ teamId: null })}
        teamMap={teamMap}
        blastRadius={blastRadius()}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    fireEvent.mouseDown(screen.getByRole('tab', { name: 'Details' }))
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })

  it('groups blast-radius entries by distance under the right direction headings and resolves team names', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius({
          downstream: {
            entries: [
              { serviceId: 's2', name: 'checkout-api', teamId: 't1', tier: null, healthStatus: 'HEALTHY', distance: 1 },
            ],
            depthTruncated: false,
            nodesBeyondDepth: 0,
            nodeCapTruncated: false,
            nodesBeyondCap: 0,
          },
          upstream: {
            entries: [
              { serviceId: 's3', name: 'auth-service', teamId: null, tier: null, healthStatus: 'HEALTHY', distance: 2 },
            ],
            depthTruncated: false,
            nodesBeyondDepth: 0,
            nodeCapTruncated: false,
            nodesBeyondCap: 0,
          },
        })}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    expect(screen.getByText('Downstream — impacted if this service fails')).toBeInTheDocument()
    expect(screen.getByText('Upstream — what this service depends on')).toBeInTheDocument()
    expect(screen.getByText('checkout-api')).toBeInTheDocument()
    expect(screen.getByText('1 hop away')).toBeInTheDocument()
    expect(screen.getByText('auth-service')).toBeInTheDocument()
    expect(screen.getByText('2 hops away')).toBeInTheDocument()
    expect(screen.getByText('Payments Team')).toBeInTheDocument()
    expect(screen.getByText('Unassigned')).toBeInTheDocument()
  })

  it('shows a depth-truncation notice distinct from a node-cap-truncation notice', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius({
          downstream: { entries: [], depthTruncated: true, nodesBeyondDepth: 4, nodeCapTruncated: false, nodesBeyondCap: 0 },
          upstream: { entries: [], depthTruncated: false, nodesBeyondDepth: 0, nodeCapTruncated: true, nodesBeyondCap: 12 },
        })}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    expect(screen.getByText(/Truncated at 3 hops — 4\+ services beyond this depth/)).toBeInTheDocument()
    expect(screen.getByText(/Showing the closest .* services — 12 more exist/)).toBeInTheDocument()
  })

  it('renders no truncation notices when neither cause applies', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius()}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    expect(screen.queryByText(/Truncated at/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Showing the closest/)).not.toBeInTheDocument()
  })

  it('shows a loading skeleton on the Blast Radius tab while the query is in flight', () => {
    const { container } = render(
      <InspectorPanel node={node()} teamMap={teamMap} blastRadius={undefined} isBlastRadiusLoading={true} blastRadiusError={null} />,
    )
    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
  })

  it('shows an error alert with the trace id when the blast-radius query fails', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={undefined}
        isBlastRadiusLoading={false}
        blastRadiusError={new ApiError('SERVER_ERROR', 'Something broke', 'abc123')}
      />,
    )
    expect(screen.getByText(/Something broke/)).toBeInTheDocument()
    expect(screen.getByText(/trace: abc123/)).toBeInTheDocument()
  })

  it('always renders a "View in catalog" link regardless of the active tab', () => {
    render(
      <InspectorPanel
        node={node()}
        teamMap={teamMap}
        blastRadius={blastRadius()}
        isBlastRadiusLoading={false}
        blastRadiusError={null}
      />,
    )
    expect(screen.getByRole('link', { name: /View in catalog/ })).toHaveAttribute('href', '/catalog/s1')
  })
})
