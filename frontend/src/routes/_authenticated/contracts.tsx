import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent } from '#/components/ui/card'
import { MOCK_CONTRACTS } from '#/lib/mock-data'
import { cn } from '#/lib/utils'

import type { ContractStatus } from '#/lib/mock-data'

export const Route = createFileRoute('/_authenticated/contracts')({
  component: ContractsPage,
})

function ContractsPage() {
  const breakingCount = MOCK_CONTRACTS.filter((c) => c.status === 'breaking').length
  const compatibleCount = MOCK_CONTRACTS.filter((c) => c.status === 'compatible').length
  const warningCount = MOCK_CONTRACTS.filter((c) => c.status === 'warning').length
  const staleCount = MOCK_CONTRACTS.filter((c) => c.status === 'stale').length

  return (
    <AppLayout title="Contracts" description="API compatibility status across all services">
      <div className="space-y-4">
        {/* Summary bar */}
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <SummaryChip label="Breaking" count={breakingCount} variant="breaking" />
          <SummaryChip label="Compatible" count={compatibleCount} variant="compatible" />
          <SummaryChip label="Warning" count={warningCount} variant="warning" />
          <SummaryChip label="Stale" count={staleCount} variant="stale" />
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
                  {MOCK_CONTRACTS.map((contract) => (
                    <tr key={contract.id} className="transition-colors hover:bg-muted/30">
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
                  ))}
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
        'capitalize',
        status === 'compatible' &&
          'border-[oklch(0.70_0.15_145)] bg-[oklch(0.96_0.05_145)] text-[oklch(0.38_0.14_145)] dark:border-[oklch(0.45_0.12_145)] dark:bg-[oklch(0.25_0.06_145)] dark:text-[oklch(0.72_0.16_145)]',
        status === 'breaking' &&
          'border-[oklch(0.70_0.15_28)] bg-[oklch(0.97_0.05_28)] text-[oklch(0.50_0.20_28)] dark:border-[oklch(0.55_0.15_28)] dark:bg-[oklch(0.27_0.05_28)] dark:text-[oklch(0.72_0.18_28)]',
        status === 'warning' &&
          'border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] text-[oklch(0.50_0.15_60)] dark:border-[oklch(0.60_0.15_60)] dark:bg-[oklch(0.27_0.06_80)] dark:text-[oklch(0.78_0.14_60)]',
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
}: {
  label: string
  count: number
  variant: ContractStatus
}) {
  return (
    <div
      className={cn(
        'rounded-xl border p-4 text-center',
        variant === 'breaking' && 'border-[oklch(0.70_0.15_28)] bg-[oklch(0.97_0.05_28)] dark:border-[oklch(0.45_0.15_28)] dark:bg-[oklch(0.27_0.05_28)]',
        variant === 'compatible' && 'border-[oklch(0.70_0.15_145)] bg-[oklch(0.96_0.05_145)] dark:border-[oklch(0.45_0.12_145)] dark:bg-[oklch(0.25_0.06_145)]',
        variant === 'warning' && 'border-[oklch(0.72_0.14_60)] bg-[oklch(0.97_0.06_80)] dark:border-[oklch(0.50_0.12_60)] dark:bg-[oklch(0.27_0.06_80)]',
        variant === 'stale' && 'border-border bg-muted/50',
      )}
    >
      <p
        className={cn(
          'text-2xl font-bold',
          variant === 'breaking' && 'text-[oklch(0.50_0.20_28)] dark:text-[oklch(0.72_0.18_28)]',
          variant === 'compatible' && 'text-[oklch(0.38_0.14_145)] dark:text-[oklch(0.72_0.16_145)]',
          variant === 'warning' && 'text-[oklch(0.50_0.15_60)] dark:text-[oklch(0.78_0.14_60)]',
          variant === 'stale' && 'text-muted-foreground',
        )}
      >
        {count}
      </p>
      <p className="mt-1 text-xs font-medium text-muted-foreground">{label}</p>
    </div>
  )
}
