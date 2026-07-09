import { useQuery } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'

import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { apiFetch } from '#/lib/api'
import type { PageResult } from '#/lib/registry-types'

export const Route = createFileRoute('/_authenticated/settings/tenant')({
  component: TenantPage,
})

interface PlanInfo {
  name: string
  slug: string
  maxServices: number
  maxUsers: number
  maxApiKeys: number
  maxScmConnections: number
  maxK8sClusters: number
  ssoEnabled: boolean
  rateLimitReplenish: number
  rateLimitBurst: number
}

interface TenantInfo {
  id: string
  name: string
  slug: string
  plan: PlanInfo
  createdAt: string
}

function formatLimit(value: number): string {
  return value === -1 ? 'Unlimited' : String(value)
}

function formatUsage(used: number | undefined, limit: number): string {
  const limitLabel = formatLimit(limit)
  return used === undefined ? limitLabel : `${used} / ${limitLabel}`
}

function TenantPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['tenant-info'],
    queryFn: () => apiFetch<TenantInfo>('/auth/tenant'),
  })

  const servicesUsage = useQuery({
    queryKey: ['tenant-usage', 'services'],
    queryFn: () => apiFetch<PageResult<unknown>>('/v1/registry/services?limit=1'),
  })
  const scmConnectionsUsage = useQuery({
    queryKey: ['tenant-usage', 'scm-connections'],
    queryFn: () => apiFetch<PageResult<unknown>>('/v1/ingestion/scm-connections?limit=1'),
  })
  const k8sClustersUsage = useQuery({
    queryKey: ['tenant-usage', 'k8s-clusters'],
    queryFn: () => apiFetch<PageResult<unknown>>('/v1/ingestion/k8s/clusters?limit=1'),
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
                <p className="text-xs text-muted-foreground">{data.plan.name}</p>
              </div>
              <Badge variant="outline">{data.plan.name}</Badge>
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

      {data && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Plan Limits</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3">
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">Services</dt>
                <dd className="text-sm font-medium">
                  {formatUsage(servicesUsage.data?.total, data.plan.maxServices)}
                </dd>
              </div>
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">Users</dt>
                <dd className="text-sm font-medium">{formatLimit(data.plan.maxUsers)}</dd>
              </div>
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">API Keys</dt>
                <dd className="text-sm font-medium">{formatLimit(data.plan.maxApiKeys)}</dd>
              </div>
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">SCM Connections</dt>
                <dd className="text-sm font-medium">
                  {formatUsage(scmConnectionsUsage.data?.total, data.plan.maxScmConnections)}
                </dd>
              </div>
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">K8s Clusters</dt>
                <dd className="text-sm font-medium">
                  {formatUsage(k8sClustersUsage.data?.total, data.plan.maxK8sClusters)}
                </dd>
              </div>
              <div className="rounded-lg bg-muted/50 px-4 py-3">
                <dt className="text-xs text-muted-foreground">SSO / OIDC</dt>
                <dd className="text-sm font-medium">{data.plan.ssoEnabled ? 'Included' : 'Not included'}</dd>
              </div>
            </dl>
          </CardContent>
        </Card>
      )}
    </SettingsTabsLayout>
  )
}
