import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { InlineQueryError } from '#/components/InlineQueryError'
import { TooltipProvider } from '#/components/ui/tooltip'
import { ApiError } from '#/lib/api'

describe('InlineQueryError', () => {
  it('renders the error message and trace id from an ApiError', () => {
    render(
      <TooltipProvider>
        <InlineQueryError error={new ApiError('SERVER_ERROR', 'boom', 'trace-123')} />
      </TooltipProvider>,
    )
    expect(screen.getByLabelText(/boom.*trace-123/i)).toBeInTheDocument()
  })

  it('renders a generic message for a non-ApiError', () => {
    render(
      <TooltipProvider>
        <InlineQueryError error={new Error('network down')} />
      </TooltipProvider>,
    )
    expect(screen.getByLabelText(/network down/i)).toBeInTheDocument()
  })
})
