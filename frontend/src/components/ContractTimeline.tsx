import { FileCode } from 'lucide-react'
import { Fragment } from 'react'

import { CompatibilityBadge } from '#/components/CompatibilityBadge'
import { cn } from '#/lib/utils'
import { relativeTime } from '#/lib/audit-format'

import type { ContractVersion } from '#/lib/contract-types'

export function ContractTimeline({
  versions,
  selected,
  onSelect,
}: {
  versions: ContractVersion[]
  selected: number
  onSelect: (versionNumber: number) => void
}) {
  return (
    <ol aria-label="Version timeline" className="relative space-y-1 border-l border-border pl-4">
      {versions.map((v) => {
        const active = v.versionNumber === selected
        return (
          <li key={v.id} className="relative">
            <span
              aria-hidden
              className={cn(
                'absolute -left-[21px] top-3 size-2.5 rounded-full border-2',
                active ? 'border-primary bg-primary' : 'border-border bg-background',
              )}
            />
            <button
              type="button"
              onClick={() => onSelect(v.versionNumber)}
              aria-current={active ? 'true' : undefined}
              className={cn(
                'w-full rounded-md px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50',
                active ? 'bg-muted' : 'hover:bg-muted/50',
              )}
            >
              <span className="flex flex-wrap items-center justify-between gap-1">
                <span className="font-mono text-sm font-medium">
                  v{v.versionNumber}
                  {v.label && <span className="text-muted-foreground"> · {v.label}</span>}
                </span>
                <CompatibilityBadge breaking={v.breaking} />
              </span>
              <span className="block text-xs text-muted-foreground">
                <time dateTime={v.createdAt}>{relativeTime(v.createdAt)}</time>
              </span>
              {v.source === 'discovery' && v.repositoryFullName && v.filePath && (
                <span className="mt-1 flex min-w-0 items-start gap-1.5 text-xs text-muted-foreground">
                  <FileCode aria-label="Discovered from" className="mt-0.5 size-3 shrink-0" />
                  <span className="min-w-0 break-words font-mono">
                    <span className="block">{withSlashBreaks(v.repositoryFullName)}</span>
                    <span className="block">{withSlashBreaks(v.filePath)}</span>
                  </span>
                </span>
              )}
            </button>
          </li>
        )
      })}
    </ol>
  )
}

function withSlashBreaks(path: string) {
  return path.split('/').map((part, i) => (
    <Fragment key={i}>
      {i > 0 && '/'}
      {i > 0 && <wbr />}
      {part}
    </Fragment>
  ))
}
