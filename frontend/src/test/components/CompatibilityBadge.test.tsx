import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { CompatibilityBadge } from '#/components/CompatibilityBadge'

describe('CompatibilityBadge', () => {
  it('renders nothing for an unchecked version', () => {
    const { container } = render(<CompatibilityBadge breaking={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows Breaking in the critical color', () => {
    render(<CompatibilityBadge breaking />)
    expect(screen.getByText('Breaking')).toHaveClass('text-critical')
  })

  it('shows Compatible muted', () => {
    render(<CompatibilityBadge breaking={false} />)
    const badge = screen.getByText('Compatible')
    expect(badge).toHaveClass('text-muted-foreground')
    expect(badge).not.toHaveClass('text-critical')
  })
})
