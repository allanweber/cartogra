import { AlertTriangle } from 'lucide-react'

import { Tooltip, TooltipContent, TooltipTrigger } from '#/components/ui/tooltip'
import { ApiError } from '#/lib/api'

export function InlineQueryError({ error }: { error: unknown }) {
  const message = error instanceof Error ? error.message : 'Failed to load'
  const traceId = error instanceof ApiError ? error.traceId : undefined

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <AlertTriangle
          className="inline size-3.5 text-destructive"
          aria-label={traceId ? `${message} (trace: ${traceId})` : message}
        />
      </TooltipTrigger>
      <TooltipContent>
        {message}
        {traceId && ` (trace: ${traceId})`}
      </TooltipContent>
    </Tooltip>
  )
}
