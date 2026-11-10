import { cloneElement } from 'react'
import type { ReactElement } from 'react'

import { Tooltip, TooltipContent, TooltipTrigger } from '#/components/ui/tooltip'
import { READ_ONLY_MESSAGE, useCan } from '#/lib/permissions'
import type { Action, PermissionContext } from '#/lib/permissions'

interface PermissionGuardProps {
  action: Action
  context?: PermissionContext
  children: ReactElement<{ disabled?: boolean }>
}

/** Renders a write control as the backend would treat it: unchanged, disabled with a reason (guest demo), or not at all. */
export function PermissionGuard({ action, context, children }: PermissionGuardProps) {
  const permission = useCan()(action, context)
  if (permission === 'deny') return null
  if (permission === 'allow') return children
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
