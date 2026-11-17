import { Badge } from '#/components/ui/badge'
import { cn } from '#/lib/utils'

import type { SpecChange } from '#/lib/contract-types'

const OP_LABEL: Record<SpecChange['op'], string> = { added: 'Added', removed: 'Removed', changed: 'Changed' }

function scalar(value: unknown): string | null {
  return value === null || typeof value === 'object' ? null : String(value)
}

export function ContractChangeList({ changes }: { changes: SpecChange[] }) {
  if (changes.length === 0) {
    return <p className="text-sm text-muted-foreground">No changes.</p>
  }
  return (
    <ul aria-label="Changes" className="divide-y divide-border rounded-lg border border-border">
      {changes.map((change) => {
        const before = scalar(change.before)
        const after = scalar(change.after)
        return (
          <li key={`${change.op}${change.pointer}`} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-3 py-2 text-sm">
            <Badge
              variant="outline"
              className={cn(
                'w-20 justify-center border-current',
                change.op === 'added' && 'text-success',
                change.op === 'removed' && 'text-critical',
                change.op === 'changed' && 'text-warning',
              )}
            >
              {OP_LABEL[change.op]}
            </Badge>
            <code className="break-all font-mono text-xs">{change.path.join(' › ')}</code>
            {change.op === 'changed' && before !== null && after !== null && (
              <span className="font-mono text-xs text-muted-foreground">
                {before} → {after}
              </span>
            )}
          </li>
        )
      })}
    </ul>
  )
}
