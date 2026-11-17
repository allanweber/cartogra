import { createFileRoute, Link } from '@tanstack/react-router'
import { ChevronLeft, ChevronRight, RotateCw, Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import { z } from 'zod'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { Skeleton } from '#/components/ui/skeleton'
import { CONTRACTS_PAGE_SIZE, useContracts } from '#/hooks/useContracts'
import { useDebounce } from '#/hooks/useDebounce'
import { ApiError } from '#/lib/api'
import { relativeTime } from '#/lib/audit-format'

const contractsSearchSchema = z.object({
  q: z.string().optional(),
  page: z.number().int().min(0).optional(),
})

export const Route = createFileRoute('/_authenticated/contracts/')({
  component: ContractsPage,
  validateSearch: contractsSearchSchema,
})

const HEADER = 'px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-muted-foreground'

function ContractsPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const page = search.page ?? 0
  const [query, setQuery] = useState(search.q ?? '')
  const dQuery = useDebounce(query, 400)
  useEffect(() => {
    if (dQuery !== (search.q ?? '')) {
      navigate({ search: { q: dQuery || undefined, page: undefined }, replace: true })
    }
  }, [dQuery])

  const { data, isLoading, error, refetch } = useContracts(search.q ?? '', page)
  const total = data?.total ?? 0
  const lastPage = Math.max(0, Math.ceil(total / CONTRACTS_PAGE_SIZE) - 1)

  return (
    <AppLayout title="Contracts" description="OpenAPI contracts and their version history">
      <div className="space-y-4">
        <div className="relative max-w-sm">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
          <Input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by service"
            aria-label="Search contracts by service"
            className="pl-9"
          />
        </div>

        {error ? (
          <Alert variant="destructive">
            <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
              <span>
                {error.message}
                {error instanceof ApiError && ` (trace: ${error.traceId})`}
              </span>
              <Button variant="outline" size="sm" onClick={() => refetch()} className="gap-1.5">
                <RotateCw className="size-3.5" /> Retry
              </Button>
            </AlertDescription>
          </Alert>
        ) : (
          <Card>
            <CardContent className="p-0">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border bg-muted/50">
                      <th className={HEADER}>Service</th>
                      <th className={HEADER}>Latest version</th>
                      <th className={HEADER}>Last changed</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border">
                    {isLoading ? (
                      Array.from({ length: 4 }).map((_, i) => (
                        <tr key={i}>
                          <td colSpan={3} className="px-4 py-3">
                            <Skeleton className="h-5 w-full" />
                          </td>
                        </tr>
                      ))
                    ) : data && data.items.length > 0 ? (
                      data.items.map((contract) => (
                        <tr key={contract.id} className="transition-colors hover:bg-muted/30">
                          <td className="px-4 py-3">
                            <Link
                              to="/contracts/$contractId"
                              params={{ contractId: contract.id }}
                              className="font-mono text-sm font-medium hover:underline focus-visible:underline focus-visible:outline-none"
                            >
                              {contract.serviceName ?? `Unknown service ${contract.serviceId.slice(0, 8)}`}
                            </Link>
                          </td>
                          <td className="px-4 py-3">
                            <Badge variant="outline" className="font-mono text-xs">
                              v{contract.latestVersion.versionNumber}
                              {contract.latestVersion.label && ` · ${contract.latestVersion.label}`}
                            </Badge>
                          </td>
                          <td className="px-4 py-3 text-muted-foreground">{relativeTime(contract.updatedAt)}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={3} className="px-4 py-8 text-center text-sm text-muted-foreground">
                          {search.q ? 'No contracts match this search.' : 'No contracts yet.'}
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        )}

        {total > CONTRACTS_PAGE_SIZE && (
          <div className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
            <span>
              Page {page + 1} of {lastPage + 1}
            </span>
            <Button
              variant="outline"
              size="icon"
              aria-label="Previous page"
              disabled={page === 0}
              onClick={() => navigate({ search: (prev) => ({ ...prev, page: page - 1 || undefined }) })}
            >
              <ChevronLeft className="size-4" />
            </Button>
            <Button
              variant="outline"
              size="icon"
              aria-label="Next page"
              disabled={page >= lastPage}
              onClick={() => navigate({ search: (prev) => ({ ...prev, page: page + 1 }) })}
            >
              <ChevronRight className="size-4" />
            </Button>
          </div>
        )}
      </div>
    </AppLayout>
  )
}
