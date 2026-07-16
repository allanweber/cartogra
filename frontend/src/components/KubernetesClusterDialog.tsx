import { useMutation, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { Switch } from '#/components/ui/switch'
import { Textarea } from '#/components/ui/textarea'
import { ToggleGroup, ToggleGroupItem } from '#/components/ui/toggle-group'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'

export interface KubernetesCluster {
  id: string
  tenantId: string
  name: string
  source: 'KUBECONFIG' | 'MANUAL'
  apiServerUrl: string
  skipTlsVerify: boolean
  status: 'CONNECTING' | 'ACTIVE' | 'ERROR'
  statusMessage: string | null
  createdAt: string
  updatedAt: string
}

interface Props {
  open: boolean
  onOpenChange: (open: boolean) => void
  cluster?: KubernetesCluster
  onSuccess?: () => void
}

const statusConfig = {
  ACTIVE: { dot: 'bg-green-500', label: 'Active', text: 'text-green-600 dark:text-green-400' },
  CONNECTING: { dot: 'bg-amber-500', label: 'Connecting…', text: 'text-amber-600 dark:text-amber-400' },
  ERROR: { dot: 'bg-red-500', label: 'Error', text: 'text-red-600 dark:text-red-400' },
}

const textareaClass = 'font-mono resize-none'

export function KubernetesClusterDialog({ open, onOpenChange, cluster, onSuccess }: Props) {
  const isEdit = !!cluster
  const queryClient = useQueryClient()

  const [name, setName] = useState('')
  const [source, setSource] = useState<'KUBECONFIG' | 'MANUAL'>('KUBECONFIG')
  const [kubeconfig, setKubeconfig] = useState('')
  const [apiServerUrl, setApiServerUrl] = useState('')
  const [caCertPem, setCaCertPem] = useState('')
  const [saToken, setSaToken] = useState('')
  const [skipTlsVerify, setSkipTlsVerify] = useState(false)
  const [removeConfirm, setRemoveConfirm] = useState(false)

  useEffect(() => {
    if (open) {
      setName(cluster?.name ?? '')
      setSource(cluster?.source ?? 'KUBECONFIG')
      setKubeconfig('')
      setApiServerUrl(cluster?.apiServerUrl ?? '')
      setCaCertPem('')
      setSaToken('')
      setSkipTlsVerify(cluster?.skipTlsVerify ?? false)
      setRemoveConfirm(false)
    }
  }, [open, cluster])

  const saveMutation = useMutation({
    mutationFn: () => {
      if (isEdit) {
        const body: Record<string, unknown> = { name, source }
        if (source === 'KUBECONFIG') {
          if (kubeconfig) body.kubeconfig = kubeconfig
        } else {
          body.apiServerUrl = apiServerUrl
          if (caCertPem) body.caCertPem = caCertPem
          if (saToken) body.saToken = saToken
          body.skipTlsVerify = skipTlsVerify
        }
        return apiMutate<KubernetesCluster>(`/v1/ingestion/k8s/clusters/${cluster.id}`, body, 'PUT')
      }
      const body: Record<string, unknown> = { name, source }
      if (source === 'KUBECONFIG') {
        body.kubeconfig = kubeconfig
      } else {
        body.apiServerUrl = apiServerUrl
        if (caCertPem) body.caCertPem = caCertPem
        if (saToken) body.saToken = saToken
        body.skipTlsVerify = skipTlsVerify
      }
      return apiMutate<KubernetesCluster>('/v1/ingestion/k8s/clusters', body, 'POST')
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['k8s-clusters'] })
      toast.success(isEdit ? 'Cluster updated' : 'Cluster connected')
      onOpenChange(false)
      onSuccess?.()
    },
    onError: (err) => {
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  const removeMutation = useMutation({
    mutationFn: () => {
      if (!cluster) throw new Error('No cluster to remove')
      return apiFetch<void>(`/v1/ingestion/k8s/clusters/${cluster.id}`, { method: 'DELETE' })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['k8s-clusters'] })
      toast.success('Cluster removed')
      onOpenChange(false)
    },
    onError: (err) => {
      setRemoveConfirm(false)
      toast.error(err.message, {
        description: err instanceof ApiError ? `trace: ${err.traceId}` : undefined,
      })
    },
  })

  function handleClose() {
    if (saveMutation.isPending || removeMutation.isPending) return
    onOpenChange(false)
  }

  const submitError = saveMutation.error ?? removeMutation.error
  const isPending = saveMutation.isPending || removeMutation.isPending
  const clusterStatus = cluster?.status ? statusConfig[cluster.status] : null

  return (
    <Dialog open={open} onOpenChange={handleClose}>
      <DialogContent
        className="gap-0 p-0 sm:max-w-[520px]"
        showCloseButton={false}
        onInteractOutside={(e) => e.preventDefault()}
      >
        <div className="border-b px-6 py-5">
          <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
            {isEdit ? 'Manage' : 'Connect'} · Kubernetes
          </p>
          <DialogDescription className="sr-only">
            {isEdit ? 'Manage this Kubernetes cluster connection.' : 'Connect a Kubernetes cluster.'}
          </DialogDescription>
          <div className="mt-1 flex items-start justify-between gap-4">
            <DialogTitle className="text-2xl font-bold">Cluster</DialogTitle>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleClose}
              className="mt-1 text-muted-foreground opacity-70 hover:opacity-100"
            >
              <X className="size-4" />
              <span className="sr-only">Close</span>
            </Button>
          </div>
        </div>

        <form
          className="flex min-w-0 flex-col"
          onSubmit={(e) => { e.preventDefault(); saveMutation.mutate() }}
        >
          <div className="space-y-5 px-6 py-6">
            {clusterStatus && (
              <div className="flex flex-col gap-1 rounded-lg bg-muted/60 px-4 py-2.5">
                <span className={`flex shrink-0 items-center gap-1.5 text-xs font-medium ${clusterStatus.text}`}>
                  <span className={`size-1.5 rounded-full ${clusterStatus.dot}`} />
                  {clusterStatus.label}
                </span>
                {cluster?.statusMessage && (
                  <span className="min-w-0 break-words text-xs text-muted-foreground">{cluster.statusMessage}</span>
                )}
              </div>
            )}

            {submitError && (
              <Alert variant="destructive">
                <AlertDescription>
                  {submitError.message}
                  {submitError instanceof ApiError && ` (trace: ${submitError.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            <div className="space-y-1.5">
              <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                Cluster name
                <span className="text-destructive">*</span>
              </label>
              <Input
                placeholder="e.g. production-us-east"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>

            <div className="space-y-1.5">
              <label className="text-sm font-medium text-foreground">Auth method</label>
              <ToggleGroup
                type="single"
                value={source}
                onValueChange={(v) => { if (v && !isEdit) setSource(v as 'KUBECONFIG' | 'MANUAL') }}
                className="justify-start"
                disabled={isEdit}
              >
                <ToggleGroupItem value="KUBECONFIG" className="px-3 text-xs">
                  Kubeconfig
                </ToggleGroupItem>
                <ToggleGroupItem value="MANUAL" className="px-3 text-xs">
                  Service account
                </ToggleGroupItem>
              </ToggleGroup>
            </div>

            {source === 'KUBECONFIG' ? (
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Kubeconfig
                    {!isEdit && <span className="text-destructive">*</span>}
                  </label>
                  <label className="cursor-pointer inline-flex items-center gap-1 rounded-md border border-input bg-transparent px-2.5 py-1 text-xs font-medium text-foreground transition-colors hover:bg-muted">
                    Upload file
                    <Input
                      type="file"
                      accept=".yaml,.yml,.kubeconfig,.conf,"
                      className="sr-only"
                      onChange={(e) => {
                        const file = e.target.files?.[0]
                        if (!file) return
                        const reader = new FileReader()
                        reader.onload = (ev) => setKubeconfig(ev.target!.result as string)
                        reader.readAsText(file)
                        e.target.value = ''
                      }}
                    />
                  </label>
                </div>
                <Textarea
                  className={textareaClass}
                  rows={8}
                  placeholder={isEdit ? '••••••• (leave blank to keep)' : 'Paste your kubeconfig YAML here…'}
                  value={kubeconfig}
                  onChange={(e) => setKubeconfig(e.target.value)}
                  required={!isEdit}
                  autoComplete="off"
                  spellCheck={false}
                />
                <p className="text-xs text-muted-foreground">
                  The active context from your kubeconfig will be used.
                </p>
              </div>
            ) : (
              <>
                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    API server URL
                    <span className="text-destructive">*</span>
                  </label>
                  <Input
                    placeholder="https://your-cluster:6443"
                    value={apiServerUrl}
                    onChange={(e) => setApiServerUrl(e.target.value)}
                    required
                  />
                </div>

                <div className="space-y-1.5">
                  <label className="text-sm font-medium text-foreground">CA certificate (PEM)</label>
                  <Textarea
                    className={textareaClass}
                    rows={5}
                    placeholder={isEdit ? '••••••• (leave blank to keep)' : '-----BEGIN CERTIFICATE-----\n…\n-----END CERTIFICATE-----'}
                    value={caCertPem}
                    onChange={(e) => setCaCertPem(e.target.value)}
                    autoComplete="off"
                    spellCheck={false}
                  />
                </div>

                <div className="space-y-1.5">
                  <label className="flex items-center gap-1.5 text-sm font-medium text-foreground">
                    Service account token
                    {!isEdit && <span className="text-destructive">*</span>}
                  </label>
                  <Input
                    type="password"
                    placeholder={isEdit ? '••••••• (leave blank to keep)' : 'eyJhbGci…'}
                    value={saToken}
                    onChange={(e) => setSaToken(e.target.value)}
                    required={!isEdit}
                    autoComplete="new-password"
                  />
                </div>

                <div
                  className="flex cursor-pointer items-center gap-3 pointer-coarse:py-2"
                  onClick={() => setSkipTlsVerify((v) => !v)}
                >
                  <Switch
                    checked={skipTlsVerify}
                    onCheckedChange={setSkipTlsVerify}
                    onClick={(e) => e.stopPropagation()}
                  />
                  <span className="text-sm font-medium text-foreground">Skip TLS verification</span>
                  {skipTlsVerify && (
                    <span className="text-xs text-amber-600 dark:text-amber-400">Not recommended for production</span>
                  )}
                </div>
              </>
            )}

            <p className="text-xs text-muted-foreground">
              Credentials are encrypted at rest and never shown after saving.
            </p>
          </div>

          <div className={`flex items-center border-t px-6 py-4 ${isEdit ? 'justify-between' : 'justify-end gap-3'}`}>
            {isEdit && (
              removeConfirm ? (
                <div className="flex items-center gap-2">
                  <span className="text-sm text-muted-foreground">Remove cluster?</span>
                  <Button
                    type="button"
                    variant="destructive"
                    size="sm"
                    disabled={isPending}
                    onClick={() => removeMutation.mutate()}
                  >
                    {removeMutation.isPending ? 'Removing…' : 'Confirm'}
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setRemoveConfirm(false)}
                  >
                    Never mind
                  </Button>
                </div>
              ) : (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                  onClick={() => setRemoveConfirm(true)}
                  disabled={isPending}
                >
                  Remove cluster
                </Button>
              )
            )}

            <div className="flex gap-3">
              <Button type="button" variant="outline" onClick={handleClose} disabled={isPending}>
                Cancel
              </Button>
              <Button type="submit" disabled={isPending}>
                {saveMutation.isPending ? 'Saving…' : isEdit ? 'Save changes' : 'Connect'}
              </Button>
            </div>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
