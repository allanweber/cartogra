import { createFileRoute } from '@tanstack/react-router'
import { useState } from 'react'
import { toast } from 'sonner'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { MOCK_CONTRACTS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { ContractStatus } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/contracts')({
  component: ContractsPage,
})

type StatusFilter = ContractStatus | 'all'

function ContractsPage() {
  const [filter, setFilter] = useState<StatusFilter>('all')

  const breakingCount = MOCK_CONTRACTS.filter((c) => c.status === 'breaking').length
  const compatibleCount = MOCK_CONTRACTS.filter((c) => c.status === 'compatible').length
  const warningCount = MOCK_CONTRACTS.filter((c) => c.status === 'warning').length
  const staleCount = MOCK_CONTRACTS.filter((c) => c.status === 'stale').length

  const filtered = filter === 'all' ? MOCK_CONTRACTS : MOCK_CONTRACTS.filter((c) => c.status === filter)

  function openDiff(contractName: string) {
    toast.info(`Diff view for ${contractName} isn't available yet`, {
      description: 'The contract diff viewer ships in a later phase.',
    })
  }

  return (
    <AppLayout title="Contracts" description="API compatibility status across all services">
      <div className="space-y-4">
        {/* Summary bar */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <SummaryChip
            label="Breaking"
            count={breakingCount}
            variant="breaking"
            active={filter === 'breaking'}
            onClick={() => setFilter((f) => (f === 'breaking' ? 'all' : 'breaking'))}
          />
          <SummaryChip
            label="Compatible"
            count={compatibleCount}
            variant="compatible"
            active={filter === 'compatible'}
            onClick={() => setFilter((f) => (f === 'compatible' ? 'all' : 'compatible'))}
          />
          <SummaryChip
            label="Warning"
            count={warningCount}
            variant="warning"
            active={filter === 'warning'}
            onClick={() => setFilter((f) => (f === 'warning' ? 'all' : 'warning'))}
          />
          <SummaryChip
            label="Stale"
            count={staleCount}
            variant="stale"
            active={filter === 'stale'}
            onClick={() => setFilter((f) => (f === 'stale' ? 'all' : 'stale'))}
          />
        </div>

        {/* Contracts table */}
        <Card>
          <CardContent className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/50">
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Contract
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Service
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Version
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Status
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Consumers
                    </th>
                    <th className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                      Last Changed
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {filtered.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-4 py-8 text-center text-sm text-muted-foreground">
                        No contracts match this filter.
                      </td>
                    </tr>
                  ) : (
                    filtered.map((contract) => (
                      <tr
                        key={contract.id}
                        role="button"
                        tabIndex={0}
                        onClick={() => openDiff(contract.name)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault()
                            openDiff(contract.name)
                          }
                        }}
                        className="cursor-pointer transition-colors hover:bg-muted/30 focus-visible:bg-muted/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                      >
                        <td className="px-4 py-3 font-mono text-sm font-medium">
                          {contract.name}
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{contract.service}</td>
                        <td className="px-4 py-3">
                          <Badge variant="outline" className="font-mono text-xs">
                            {contract.version}
                          </Badge>
                        </td>
                        <td className="px-4 py-3">
                          <ContractStatusBadge status={contract.status} />
                        </td>
                        <td className="px-4 py-3 text-muted-foreground">{contract.consumers}</td>
                        <td className="px-4 py-3 text-muted-foreground">{contract.lastChanged}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      </div>
    </AppLayout>
  )
}

function ContractStatusBadge({ status }: { status: ContractStatus }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'capitalize border-current',
        status === 'compatible' && 'text-success',
        status === 'breaking' && 'text-critical',
        status === 'warning' && 'text-warning',
        status === 'stale' && 'text-muted-foreground',
      )}
    >
      {status}
    </Badge>
  )
}

function SummaryChip({
  label,
  count,
  variant,
  active,
  onClick,
}: {
  label: string
  count: number
  variant: ContractStatus
  active: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-xl border p-4 text-center transition-all',
        active && variant === 'breaking' && 'border-critical bg-critical-subtle',
        active && variant === 'compatible' && 'border-success bg-success-subtle',
        active && variant === 'warning' && 'border-warning bg-warning-subtle',
        active && variant === 'stale' && 'border-border bg-muted',
        !active && 'border-border bg-card hover:bg-muted/50',
      )}
    >
      <p
        className={cn(
          'text-2xl font-bold',
          variant === 'breaking' && 'text-critical',
          variant === 'compatible' && 'text-success',
          variant === 'warning' && 'text-warning',
          variant === 'stale' && 'text-muted-foreground',
        )}
      >
        {count}
      </p>
      <p className="mt-1 text-xs font-medium text-muted-foreground">{label}</p>
    </button>
  )
}
