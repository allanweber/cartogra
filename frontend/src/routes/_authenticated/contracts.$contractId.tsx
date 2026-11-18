import { createFileRoute, Link } from '@tanstack/react-router'
import { ArrowLeft } from 'lucide-react'
import { z } from 'zod'

import { AppLayout } from '#/components/AppLayout'
import { ContractChangeList } from '#/components/ContractChangeList'
import { ContractTimeline } from '#/components/ContractTimeline'
import { SideBySideDiff } from '#/components/SideBySideDiff'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { useContract, useContractVersions, useVersionDiff } from '#/hooks/useContracts'
import { ApiError } from '#/lib/api'

export const Route = createFileRoute('/_authenticated/contracts/$contractId')({
  component: ContractDetailPage,
  validateSearch: z.object({ v: z.number().int().min(1).optional() }),
})

function QueryAlert({ error }: { error: Error }) {
  return (
    <Alert variant="destructive">
      <AlertDescription>
        {error.message}
        {error instanceof ApiError && ` (trace: ${error.traceId})`}
      </AlertDescription>
    </Alert>
  )
}

function ContractDetailPage() {
  const { contractId } = Route.useParams()
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const contract = useContract(contractId)
  const versions = useContractVersions(contractId)
  const selected = search.v ?? contract.data?.latestVersion.versionNumber
  const diff = useVersionDiff(contractId, selected)

  const title = contract.data
    ? (contract.data.serviceName ?? `Unknown service ${contract.data.serviceId.slice(0, 8)}`)
    : 'Contract'

  return (
    <AppLayout title={title} eyebrow="Contracts" description="Version timeline and diff against the previous version">
      <div className="space-y-4">
        <Link to="/contracts" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-4" /> All contracts
        </Link>

        {contract.error ? (
          <QueryAlert error={contract.error} />
        ) : (
          <div className="grid gap-4 lg:grid-cols-[14rem_minmax(0,1fr)]">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Versions</CardTitle>
              </CardHeader>
              <CardContent>
                {versions.error ? (
                  <QueryAlert error={versions.error} />
                ) : versions.isLoading || selected === undefined ? (
                  <div className="space-y-2">
                    {Array.from({ length: 3 }).map((_, i) => (
                      <Skeleton key={i} className="h-12 w-full" />
                    ))}
                  </div>
                ) : (
                  <ContractTimeline
                    versions={versions.data?.items ?? []}
                    selected={selected}
                    onSelect={(v) => navigate({ search: { v }, replace: true })}
                  />
                )}
              </CardContent>
            </Card>

            <div className="min-w-0 space-y-4">
              {diff.error ? (
                <QueryAlert error={diff.error} />
              ) : !diff.data ? (
                <>
                  <Skeleton className="h-24 w-full" />
                  <Skeleton className="h-96 w-full" />
                </>
              ) : (
                <>
                  <Card>
                    <CardHeader>
                      <CardTitle className="text-sm">
                        {diff.data.from
                          ? `Changes v${diff.data.from.versionNumber} → v${diff.data.to.versionNumber}`
                          : `v${diff.data.to.versionNumber} · first version`}
                      </CardTitle>
                    </CardHeader>
                    <CardContent>
                      {diff.data.from ? (
                        <ContractChangeList changes={diff.data.changes} />
                      ) : (
                        <p className="text-sm text-muted-foreground">Nothing to compare against: every line is new.</p>
                      )}
                    </CardContent>
                  </Card>
                  <SideBySideDiff
                    lines={diff.data.lines}
                    fromLabel={diff.data.from ? `v${diff.data.from.versionNumber}` : 'empty'}
                    toLabel={`v${diff.data.to.versionNumber}`}
                  />
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </AppLayout>
  )
}
