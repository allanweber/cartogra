import { CompatibilityBadge } from '#/components/CompatibilityBadge'
import { cn } from '#/lib/utils'

import type { Compatibility, CompatibilityChange } from '#/lib/contract-types'

function ChangeRow({ change }: { change: CompatibilityChange }) {
  return (
    <li className="space-y-0.5 px-3 py-2 text-sm">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <code className={cn('font-mono text-xs', change.breaking ? 'text-critical' : 'text-muted-foreground')}>
          {change.rule}
        </code>
        <span className="break-all font-mono text-xs">
          {change.method && `${change.method} `}
          {change.path}
          {change.location && <span className="text-muted-foreground"> · {change.location}</span>}
        </span>
      </div>
      <p className="text-xs text-muted-foreground">{change.message}</p>
    </li>
  )
}

function Group({ label, changes }: { label: string; changes: CompatibilityChange[] }) {
  if (changes.length === 0) return null
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        {label} ({changes.length})
      </p>
      <ul aria-label={label} className="divide-y divide-border rounded-lg border border-border">
        {changes.map((change, i) => (
          <ChangeRow key={`${change.rule}${change.method}${change.path}${change.location}${i}`} change={change} />
        ))}
      </ul>
    </div>
  )
}

/** The engine's rule hits for a version, breaking changes first. */
export function ContractCompatibility({ compatibility }: { compatibility: Compatibility }) {
  const breaking = compatibility.changes.filter((c) => c.breaking)
  const compatible = compatibility.changes.filter((c) => !c.breaking)
  return (
    <div className="space-y-3">
      <CompatibilityBadge breaking={compatibility.isBreaking} />
      {compatibility.changes.length === 0 ? (
        <p className="text-sm text-muted-foreground">No breaking changes found by the compatibility rules.</p>
      ) : (
        <>
          <Group label="Breaking changes" changes={breaking} />
          <Group label="Compatible changes" changes={compatible} />
        </>
      )}
    </div>
  )
}
