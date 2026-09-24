import { AlertCircle, AlertTriangle, CircleCheck } from 'lucide-react'

import { Popover, PopoverContent, PopoverTrigger } from '#/components/ui/popover'
import { computeRiskBreakdown, riskBandLabel } from '#/lib/risk-score'
import { cn } from '#/lib/utils'

import type { RiskBand, RiskBreakdown } from '#/lib/risk-score'
import type { RegistryService } from '#/lib/registry-types'

function riskTextClass(band: RiskBand): string {
  if (band === 'high') return 'text-critical'
  if (band === 'moderate') return 'text-warning'
  return 'text-success'
}

function riskBarClass(band: RiskBand): string {
  if (band === 'high') return 'bg-critical'
  if (band === 'moderate') return 'bg-warning'
  return 'bg-success'
}

function riskRingClass(band: RiskBand): string {
  if (band === 'high') return 'border-critical text-critical'
  if (band === 'moderate') return 'border-warning text-warning'
  return 'border-success text-success'
}

// Pairs the risk band with a distinct icon, not just a hue, per DESIGN.md's
// color-blind-safe status signaling rule — this is one of the two loudest
// decision signals in the product, so it can't rely on color alone.
function RiskIcon({ band, className }: { band: RiskBand; className?: string }) {
  if (band === 'high') return <AlertTriangle className={className} aria-hidden="true" />
  if (band === 'moderate') return <AlertCircle className={className} aria-hidden="true" />
  return <CircleCheck className={className} aria-hidden="true" />
}

function RiskBreakdownContent({ breakdown }: { breakdown: RiskBreakdown }) {
  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-3">
        <p className="flex items-center gap-1.5 text-sm font-semibold">
          <RiskIcon band={breakdown.band} className={cn('size-3.5', riskTextClass(breakdown.band))} />
          Risk Score
        </p>
        <span className={cn('text-sm font-semibold tabular-nums', riskTextClass(breakdown.band))}>
          {breakdown.score}
        </span>
      </div>
      <p className="text-xs text-muted-foreground">
        {riskBandLabel(breakdown.band)} — a triage signal, not a guarantee.
      </p>
      <ul className="space-y-1 border-t border-border pt-2 text-xs">
        {breakdown.factors.map((factor) => (
          <li key={factor.label} className="flex items-center justify-between gap-3">
            <span className="text-muted-foreground">{factor.label}</span>
            <span className="font-medium tabular-nums">+{factor.points}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// stopPropagation keeps a click on the trigger from also firing the surrounding card/row
// Link's navigation — these badges are used inside fully-clickable catalog cards/rows.
function stopPropagation(event: React.SyntheticEvent) {
  event.stopPropagation()
}

/** Grid-card footer: bare numeral + severity icon. */
export function RiskScoreInline({ service, className }: { service: RegistryService; className?: string }) {
  const breakdown = computeRiskBreakdown(service)
  return (
    <Popover>
      <PopoverTrigger
        onClick={stopPropagation}
        className={cn(
          '-m-1 inline-flex items-center gap-1 rounded-md p-1 text-sm font-semibold tabular-nums transition-colors hover:bg-muted/60 hover:underline focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
          riskTextClass(breakdown.band),
          className,
        )}
        aria-label={`Risk score ${breakdown.score}, ${riskBandLabel(breakdown.band)}. View breakdown.`}
      >
        <RiskIcon band={breakdown.band} className="size-3.5" />
        {breakdown.score}
      </PopoverTrigger>
      <PopoverContent align="end">
        <RiskBreakdownContent breakdown={breakdown} />
      </PopoverContent>
    </Popover>
  )
}

/** List-row cell: numeral + icon + progress bar. */
export function RiskScoreBar({ service }: { service: RegistryService }) {
  const breakdown = computeRiskBreakdown(service)
  return (
    <Popover>
      <PopoverTrigger
        onClick={stopPropagation}
        className="-m-1 flex flex-1 items-center gap-1.5 rounded-md p-1 transition-colors hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        aria-label={`Risk score ${breakdown.score}, ${riskBandLabel(breakdown.band)}. View breakdown.`}
      >
        <RiskIcon band={breakdown.band} className={cn('size-3.5 shrink-0', riskTextClass(breakdown.band))} />
        <span className={cn('min-w-[2ch] text-sm font-semibold tabular-nums', riskTextClass(breakdown.band))}>
          {breakdown.score}
        </span>
        <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
          <span
            className={cn('block h-full rounded-full transition-all', riskBarClass(breakdown.band))}
            style={{ width: `${breakdown.score}%` }}
          />
        </span>
      </PopoverTrigger>
      <PopoverContent align="end">
        <RiskBreakdownContent breakdown={breakdown} />
      </PopoverContent>
    </Popover>
  )
}

/** Detail-header ring. */
export function RiskScoreRing({ service }: { service: RegistryService }) {
  const breakdown = computeRiskBreakdown(service)
  return (
    <Popover>
      <PopoverTrigger
        className={cn(
          'flex h-14 w-14 items-center justify-center rounded-full border-2 text-xl font-bold tabular-nums transition-colors hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50',
          riskRingClass(breakdown.band),
        )}
        aria-label={`Risk score ${breakdown.score}, ${riskBandLabel(breakdown.band)}. View breakdown.`}
      >
        {breakdown.score}
      </PopoverTrigger>
      <PopoverContent align="end">
        <RiskBreakdownContent breakdown={breakdown} />
      </PopoverContent>
    </Popover>
  )
}
