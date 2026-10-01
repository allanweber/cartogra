import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { SpofBadge } from '#/components/SpofBadge'

import type { Spof } from '#/lib/topology-types'

const nodesById = new Map([
  ['s1', { name: 'checkout-api' }],
  ['s2', { name: 'payments-api' }],
])

function spof(overrides: Partial<Spof> = {}): Spof {
  return {
    serviceId: 's1',
    name: 'checkout-api',
    teamId: null,
    tier: 'STANDARD',
    healthStatus: 'HEALTHY',
    fanIn: 6,
    severity: 'warning',
    ...overrides,
  }
}

describe('SpofBadge', () => {
  it('renders nothing for an empty SPOF list', () => {
    const { container } = render(<SpofBadge spofs={[]} nodesById={nodesById} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the count and opens a popover listing each SPOF by name and fan-in', () => {
    render(<SpofBadge spofs={[spof()]} nodesById={nodesById} />)

    const trigger = screen.getByRole('button', { name: /1 single point of failure/i })
    fireEvent.click(trigger)

    expect(screen.getByText(/checkout-api — 6 dependents/)).toBeInTheDocument()
  })

  it('pluralizes the count for more than one SPOF', () => {
    const spofs: Spof[] = [spof({ serviceId: 's1' }), spof({ serviceId: 's2', name: 'payments-api' })]
    render(<SpofBadge spofs={spofs} nodesById={nodesById} />)

    expect(screen.getByText('2 single points of failure')).toBeInTheDocument()
  })

  it('colors the badge critical when any relevant SPOF is critical severity', () => {
    render(<SpofBadge spofs={[spof({ severity: 'critical' })]} nodesById={nodesById} />)

    expect(screen.getByRole('button', { name: /single point of failure/i })).toHaveClass('text-critical')
  })

  it('colors the badge warning when no relevant SPOF is critical severity', () => {
    render(<SpofBadge spofs={[spof({ severity: 'warning' })]} nodesById={nodesById} />)

    expect(screen.getByRole('button', { name: /single point of failure/i })).toHaveClass('text-warning')
  })

  it('onlyForServiceId narrows to that service and renders nothing when it is not flagged', () => {
    const spofs: Spof[] = [spof({ serviceId: 's1' }), spof({ serviceId: 's2', name: 'payments-api' })]
    const { container, rerender } = render(<SpofBadge spofs={spofs} nodesById={nodesById} onlyForServiceId="s1" />)
    expect(screen.getByText('1 single point of failure')).toBeInTheDocument()

    rerender(<SpofBadge spofs={spofs} nodesById={nodesById} onlyForServiceId="not-flagged" />)
    expect(container).toBeEmptyDOMElement()
  })
})
