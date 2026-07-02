import { useQuery } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'

import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { apiFetch } from '#/lib/api'

export const Route = createFileRoute('/_authenticated/settings/tenant')({
  component: TenantPage,
})

interface TenantInfo {
  id: string
  name: string
  slug: string
  plan: string
  createdAt: string
}

function TenantPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['tenant-info'],
    queryFn: () => apiFetch<TenantInfo>('/auth/tenant'),
  })

  return (
    <SettingsTabsLayout>
      {isLoading && <Skeleton className="h-40 w-full rounded-lg" />}

      {error && (
        <Alert variant="destructive">
          <AlertDescription>Failed to load tenant information.</AlertDescription>
        </Alert>
      )}

      {data && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Workspace</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Name</p>
                <p className="text-xs text-muted-foreground">{data.name}</p>
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Slug</p>
                <p className="font-mono text-xs text-muted-foreground">{data.slug}</p>
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Plan</p>
                <p className="text-xs text-muted-foreground">{data.plan}</p>
              </div>
              <Badge variant="outline">{data.plan}</Badge>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-muted/50 px-4 py-3">
              <div>
                <p className="text-sm font-medium">Created</p>
                <p className="text-xs text-muted-foreground">
                  {new Date(data.createdAt).toLocaleDateString(undefined, { dateStyle: 'medium' })}
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </SettingsTabsLayout>
  )
}
