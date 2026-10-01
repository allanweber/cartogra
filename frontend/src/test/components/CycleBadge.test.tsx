import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { CycleBadge } from '#/components/CycleBadge'

import type { Cycle } from '#/lib/topology-types'

const nodesById = new Map([
  ['s1', { name: 'checkout-api' }],
  ['s2', { name: 'payments-api' }],
  ['s3', { name: 'ledger-service' }],
])

describe('CycleBadge', () => {
  it('renders nothing for an empty cycle list', () => {
    const { container } = render(<CycleBadge cycles={[]} nodesById={nodesById} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders the count and opens a popover listing each cycle by member name', () => {
    const cycles: Cycle[] = [{ members: ['s1', 's2', 's3'], length: 3 }]
    render(<CycleBadge cycles={cycles} nodesById={nodesById} />)

    const trigger = screen.getByRole('button', { name: /1 dependency cycle/i })
    fireEvent.click(trigger)

    expect(screen.getByText('checkout-api → payments-api → ledger-service → checkout-api')).toBeInTheDocument()
  })

  it('pluralizes the count for more than one cycle', () => {
    const cycles: Cycle[] = [
      { members: ['s1', 's2'], length: 2 },
      { members: ['s2', 's3'], length: 2 },
    ]
    render(<CycleBadge cycles={cycles} nodesById={nodesById} />)

    expect(screen.getByText('2 cycles')).toBeInTheDocument()
  })

  it('falls back to the raw id when a member has no known name', () => {
    const cycles: Cycle[] = [{ members: ['s1', 'unknown-id'], length: 2 }]
    render(<CycleBadge cycles={cycles} nodesById={nodesById} />)
    fireEvent.click(screen.getByRole('button'))

    expect(screen.getByText('checkout-api → unknown-id → checkout-api')).toBeInTheDocument()
  })

  it('onlyForServiceId narrows to cycles containing that service and renders nothing when it is in none', () => {
    const cycles: Cycle[] = [
      { members: ['s1', 's2'], length: 2 },
      { members: ['s2', 's3'], length: 2 },
    ]
    const { container, rerender } = render(<CycleBadge cycles={cycles} nodesById={nodesById} onlyForServiceId="s1" />)
    expect(screen.getByText('1 cycle')).toBeInTheDocument()

    rerender(<CycleBadge cycles={cycles} nodesById={nodesById} onlyForServiceId="not-in-any-cycle" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows a plus count and a cap note when the cycle list is truncated', () => {
    const cycles: Cycle[] = [{ members: ['s1', 's2'], length: 2 }]
    render(<CycleBadge cycles={cycles} nodesById={nodesById} truncated />)

    fireEvent.click(screen.getByRole('button', { name: /1\+ dependency cycles/i }))

    expect(screen.getByText(/hit its cap/i)).toBeInTheDocument()
  })
})
