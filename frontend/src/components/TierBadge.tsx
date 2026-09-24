import { cn } from '#/lib/utils'

import type { ServiceTierValue } from '#/lib/registry-types'

// Same badge chrome everywhere a tier renders — border-current + text color, neutral
// background, per DESIGN.md's severity-badge rule — instead of bare colored text in
// some places and a filled badge in others.
export function TierBadge({ tier }: { tier: ServiceTierValue | null }) {
  if (!tier) return null
  const isCritical = tier === 'CRITICAL'
  return (
    <span
      className={cn(
        'rounded border border-current px-1.5 py-0.5 text-xs font-semibold uppercase tracking-wide',
        isCritical ? 'text-critical' : 'text-muted-foreground',
      )}
    >
      {tier}
    </span>
  )
}
