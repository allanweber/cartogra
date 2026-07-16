import { useQuery } from '@tanstack/react-query'
import { Activity, Cloud, Hexagon, Server } from 'lucide-react'
import { useState } from 'react'

import type { KubernetesCluster } from '#/components/KubernetesClusterDialog'
import { KubernetesClusterDialog } from '#/components/KubernetesClusterDialog'
import type { ScmConnection } from '#/components/ScmConnectionDialog'
import { ScmConnectionDialog } from '#/components/ScmConnectionDialog'
import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { Tooltip, TooltipContent, TooltipTrigger } from '#/components/ui/tooltip'
import { apiFetch } from '#/lib/api'
import type { PageResult, TenantInfo } from '#/lib/registry-types'
import { useWizardStore } from '#/stores/useWizardStore'
import { createFileRoute } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/settings/connections')({
  component: ConnectionsPage,
})

interface ProviderItem {
  key: string
  label: string
  description: string
  icon: React.ReactNode
  active: boolean
}

const PROVIDERS: ProviderItem[] = [
  {
    key: 'github',
    label: 'GitHub',
    description: 'Sync repositories and pull requests',
    icon: (
      <svg viewBox="0 0 24 24" className="size-5" fill="currentColor" aria-hidden="true">
        <path d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.482 0-.237-.009-.868-.013-1.703-2.782.604-3.369-1.342-3.369-1.342-.454-1.155-1.11-1.463-1.11-1.463-.908-.62.069-.608.069-.608 1.003.07 1.531 1.03 1.531 1.03.892 1.529 2.341 1.087 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.11-4.555-4.943 0-1.091.39-1.984 1.029-2.683-.103-.253-.446-1.27.098-2.647 0 0 .84-.269 2.75 1.025A9.578 9.578 0 0 1 12 6.836a9.59 9.59 0 0 1 2.504.337c1.909-1.294 2.747-1.025 2.747-1.025.546 1.377.202 2.394.1 2.647.64.699 1.028 1.592 1.028 2.683 0 3.842-2.339 4.687-4.566 4.935.359.309.678.919.678 1.852 0 1.336-.012 2.415-.012 2.743 0 .267.18.578.688.48C19.138 20.163 22 16.418 22 12c0-5.523-4.477-10-10-10z" />
      </svg>
    ),
    active: true,
  },
  {
    key: 'azuredevops',
    label: 'Azure DevOps',
    description: 'Connect pipelines and repos',
    icon: <Hexagon className="size-5" />,
    active: true,
  },
  {
    key: 'kubernetes',
    label: 'Kubernetes',
    description: 'Monitor cluster health and deployments',
    icon: <Server className="size-5" />,
    active: true,
  },
  {
    key: 'opentelemetry',
    label: 'OpenTelemetry',
    description: 'Ingest traces from your services',
    icon: <Activity className="size-5" />,
    active: false,
  },
  {
    key: 'gitlab',
    label: 'GitLab',
    description: 'Connect GitLab repos for additional services',
    icon: <Hexagon className="size-5" />,
    active: false,
  },
  {
    key: 'datadog',
    label: 'Datadog',
    description: 'Import metrics and dashboards',
    icon: <Cloud className="size-5" />,
    active: false,
  },
]

function ConnectionsPage() {
  const openWizard = useWizardStore((s) => s.openWizard)
  const [dialogProvider, setDialogProvider] = useState<'github' | 'azuredevops' | null>(null)
  const [editConnection, setEditConnection] = useState<ScmConnection | undefined>(undefined)
  const [k8sDialogOpen, setK8sDialogOpen] = useState(false)
  const [editCluster, setEditCluster] = useState<KubernetesCluster | undefined>(undefined)

  const { data, isLoading, error } = useQuery({
    queryKey: ['scm-connections'],
    queryFn: () => apiFetch<PageResult<ScmConnection>>('/v1/ingestion/scm-connections'),
  })

  const { data: countsData } = useQuery({
    queryKey: ['services-counts-by-connection'],
    queryFn: () => apiFetch<{ counts: Record<string, number> }>('/v1/registry/services/counts-by-connection'),
  })

  const { data: clustersData } = useQuery({
    queryKey: ['k8s-clusters'],
    queryFn: () => apiFetch<PageResult<KubernetesCluster>>('/v1/ingestion/k8s/clusters'),
    refetchInterval: (query) =>
      query.state.data?.items.some((c) => c.status === 'CONNECTING') ? 2000 : false,
  })

  const { data: tenantData } = useQuery({
    queryKey: ['tenant-info'],
    queryFn: () => apiFetch<TenantInfo>('/auth/tenant'),
  })

  const connections = data?.items ?? []
  const clusters = clustersData?.items ?? []
  const failedConnections = connections.filter((c) => c.lastSyncStatus === 'FAILED')

  const maxScmConnections = tenantData?.plan.maxScmConnections
  const maxK8sClusters = tenantData?.plan.maxK8sClusters
  const scmLimitReached =
    maxScmConnections !== undefined && maxScmConnections !== -1 && connections.length >= maxScmConnections
  const k8sLimitReached =
    maxK8sClusters !== undefined && maxK8sClusters !== -1 && clusters.length >= maxK8sClusters

  function openCreate(key: string) {
    if (key === 'kubernetes') {
      setEditCluster(undefined)
      setK8sDialogOpen(true)
      return
    }
    setEditConnection(undefined)
    setDialogProvider(key as 'github' | 'azuredevops')
  }

  function openEdit(conn: ScmConnection) {
    setEditConnection(conn)
    setDialogProvider(conn.provider as 'github' | 'azuredevops')
  }

  function openEditCluster(cluster: KubernetesCluster) {
    setEditCluster(cluster)
    setK8sDialogOpen(true)
  }

  return (
    <SettingsTabsLayout>
      <div className="space-y-2">
        <div className="mb-1 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-sm font-semibold">SCM & Platform Connections</h2>
            <p className="text-xs text-muted-foreground">
              Connect your source control and infrastructure providers to start syncing services.
            </p>
          </div>
          <Button
            size="sm"
            variant="outline"
            onClick={openWizard}
          >
            Setup wizard
          </Button>
        </div>

        {isLoading && (
          <div className="space-y-2">
            {[1, 2, 3].map((i) => (
              <Skeleton key={i} className="h-16 w-full rounded-lg" />
            ))}
          </div>
        )}

        {error && (
          <Alert variant="destructive">
            <AlertDescription>Failed to load connections.</AlertDescription>
          </Alert>
        )}

        {!isLoading && !error && failedConnections.length > 0 && (
          <Alert variant="destructive">
            <AlertDescription>
              {failedConnections.length === 1
                ? `${PROVIDERS.find((p) => p.key === failedConnections[0].provider)?.label ?? failedConnections[0].provider} sync failed`
                : `${failedConnections.length} connections failed to sync`}
              {failedConnections[0].lastSyncError ? ` — ${failedConnections[0].lastSyncError}` : ''}
              . Open "Manage" below to review.
            </AlertDescription>
          </Alert>
        )}

        {!isLoading && !error && (
          <Card className="gap-0 rounded-lg py-0 divide-y divide-border">
            {PROVIDERS.map((p) => {
              if (p.key === 'kubernetes') {
                const clusterCount = clusters.length
                return (
                  <div key={p.key} className="flex items-center gap-4 px-5 py-4">
                    <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                      {p.icon}
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium">{p.label}</p>
                      {clusterCount > 0 ? (
                        <p className="text-xs text-muted-foreground truncate">
                          {clusterCount} cluster{clusterCount !== 1 ? 's' : ''} registered
                        </p>
                      ) : (
                        <p className="text-xs text-muted-foreground truncate">{p.description}</p>
                      )}
                    </div>
                    {clusterCount > 0 ? (
                      <div className="flex items-center gap-2.5 shrink-0">
                        {clusters[0].status === 'ACTIVE' && (
                          <span className="flex items-center gap-1.5 text-xs font-medium text-green-600 dark:text-green-400">
                            <span className="size-1.5 rounded-full bg-green-500" />
                            Active
                          </span>
                        )}
                        {clusters[0].status === 'CONNECTING' && (
                          <span className="flex items-center gap-1.5 text-xs font-medium text-amber-600 dark:text-amber-400">
                            <span className="size-1.5 rounded-full bg-amber-500" />
                            Connecting
                          </span>
                        )}
                        {clusters[0].status === 'ERROR' && (
                          <span className="flex items-center gap-1.5 text-xs font-medium text-red-600 dark:text-red-400">
                            <span className="size-1.5 rounded-full bg-red-500" />
                            Error
                          </span>
                        )}
                        <Button size="sm" variant="outline" onClick={() => openEditCluster(clusters[0])}>
                          Manage
                        </Button>
                      </div>
                    ) : k8sLimitReached ? (
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <span>
                            <Button size="sm" disabled>
                              Connect
                            </Button>
                          </span>
                        </TooltipTrigger>
                        <TooltipContent>
                          {maxK8sClusters === 0
                            ? 'Not included in your plan'
                            : 'Plan limit reached — upgrade to connect more clusters'}
                        </TooltipContent>
                      </Tooltip>
                    ) : (
                      <Button size="sm" onClick={() => openCreate(p.key)}>
                        Connect
                      </Button>
                    )}
                  </div>
                )
              }

              const conn = connections.find((c) => c.provider === p.key)
              return (
                <div key={p.key} className="flex items-center gap-4 px-5 py-4">
                  <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
                    {p.icon}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium">{p.label}</p>
                    {conn ? (
                      conn.lastSyncStatus === 'FAILED' && conn.lastSyncError ? (
                        <p className="text-xs text-red-600 dark:text-red-400 truncate" title={conn.lastSyncError}>
                          {conn.lastSyncError}
                        </p>
                      ) : (
                        <p className="text-xs text-muted-foreground truncate">
                          {conn.lastSyncAt
                            ? (() => {
                                const count = (countsData?.counts ?? {})[conn.id] ?? 0
                                return `Synced ${new Date(conn.lastSyncAt).toLocaleString()} · ${count} service${count !== 1 ? 's' : ''}`
                              })()
                            : 'Never synced'}
                        </p>
                      )
                    ) : (
                      <p className="text-xs text-muted-foreground truncate">{p.description}</p>
                    )}
                  </div>
                  {!p.active ? (
                    <Badge variant="outline" className="text-xs">Coming soon</Badge>
                  ) : conn ? (
                    <div className="flex items-center gap-2.5 shrink-0">
                      {conn.lastSyncStatus === 'FAILED' ? (
                        <span className="flex items-center gap-1.5 text-xs font-medium text-red-600 dark:text-red-400">
                          <span className="size-1.5 rounded-full bg-red-500" />
                          Sync failed
                        </span>
                      ) : (
                        <span className="flex items-center gap-1.5 text-xs font-medium text-green-600 dark:text-green-400">
                          <span className="size-1.5 rounded-full bg-green-500" />
                          Connected
                        </span>
                      )}
                      <Button size="sm" variant="outline" onClick={() => openEdit(conn)}>
                        Manage
                      </Button>
                    </div>
                  ) : scmLimitReached ? (
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span>
                          <Button size="sm" disabled>
                            Connect
                          </Button>
                        </span>
                      </TooltipTrigger>
                      <TooltipContent>
                        {maxScmConnections === 0
                          ? 'Not included in your plan'
                          : 'Plan limit reached — upgrade to connect more providers'}
                      </TooltipContent>
                    </Tooltip>
                  ) : (
                    <Button size="sm" onClick={() => openCreate(p.key)}>
                      Connect
                    </Button>
                  )}
                </div>
              )
            })}
          </Card>
        )}
      </div>

      {dialogProvider && (
        <ScmConnectionDialog
          key={editConnection?.id ?? dialogProvider}
          open={!!dialogProvider}
          onOpenChange={(open) => { if (!open) { setDialogProvider(null); setEditConnection(undefined) } }}
          provider={dialogProvider}
          connection={editConnection}
        />
      )}

      <KubernetesClusterDialog
        key={editCluster?.id ?? 'new-cluster'}
        open={k8sDialogOpen}
        onOpenChange={(open) => { if (!open) { setK8sDialogOpen(false); setEditCluster(undefined) } }}
        cluster={editCluster}
      />
    </SettingsTabsLayout>
  )
}
