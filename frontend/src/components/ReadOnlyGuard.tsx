import { cloneElement } from 'react'
import type { ReactElement } from 'react'

import { Tooltip, TooltipContent, TooltipTrigger } from '#/components/ui/tooltip'
import { READ_ONLY_MESSAGE, useIsReadOnly } from '#/lib/permissions'

/** Renders a write control disabled, with an explanatory tooltip, in a read-only session; otherwise unchanged. */
export function ReadOnlyGuard({ children }: { children: ReactElement<{ disabled?: boolean }> }) {
  const isReadOnly = useIsReadOnly()
  if (!isReadOnly) return children
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span tabIndex={0} className="inline-flex" data-testid="read-only-guard">
          {cloneElement(children, { disabled: true })}
        </span>
      </TooltipTrigger>
      <TooltipContent>{READ_ONLY_MESSAGE}</TooltipContent>
    </Tooltip>
  )
}
