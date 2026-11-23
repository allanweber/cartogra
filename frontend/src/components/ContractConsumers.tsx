import { Link } from '@tanstack/react-router'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { useContractConsumers } from '#/hooks/useContracts'
import { ApiError } from '#/lib/api'

export function ContractConsumers({ contractId }: { contractId: string }) {
  const consumers = useContractConsumers(contractId)
  const items = consumers.data?.pages.flatMap((page) => page.items) ?? []
  const total = consumers.data?.pages[0]?.total

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center justify-between text-sm">
          Consumers
          {total !== undefined && (
            <span className="font-mono text-muted-foreground">{total}</span>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent>
        {consumers.error ? (
          <Alert variant="destructive">
            <AlertDescription>
              {consumers.error.message}
              {consumers.error instanceof ApiError &&
                ` (trace: ${consumers.error.traceId})`}
            </AlertDescription>
          </Alert>
        ) : consumers.isLoading ? (
          <div className="space-y-2">
            {Array.from({ length: 3 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No services depend on this one.
          </p>
        ) : (
          <div className="space-y-2">
            <ul aria-label="Consumers" className="space-y-1">
              {items.map((consumer) => (
                <li key={consumer.serviceId}>
                  <Link
                    to="/catalog/$serviceId"
                    params={{ serviceId: consumer.serviceId }}
                    className="block rounded-md px-2 py-1.5 transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
                  >
                    <span className="block truncate text-sm font-medium">
                      {consumer.serviceName ??
                        `Unknown service ${consumer.serviceId.slice(0, 8)}`}
                    </span>
                    <span className="mt-1 flex flex-wrap gap-1">
                      {consumer.dependencyTypes.map((type) => (
                        <Badge key={type} variant="outline">
                          {type}
                        </Badge>
                      ))}
                      {consumer.protocols.map((protocol) => (
                        <Badge
                          key={protocol}
                          variant="outline"
                          className="font-mono"
                        >
                          {protocol}
                        </Badge>
                      ))}
                    </span>
                  </Link>
                </li>
              ))}
            </ul>
            {consumers.hasNextPage && (
              <Button
                variant="ghost"
                size="sm"
                className="w-full"
                disabled={consumers.isFetchingNextPage}
                onClick={() => consumers.fetchNextPage()}
              >
                {consumers.isFetchingNextPage ? 'Loading…' : 'Show more'}
              </Button>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

