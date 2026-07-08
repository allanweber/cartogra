import { useQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { CheckCircle2, Hexagon, PlugZap, Settings, X } from 'lucide-react'
import { useEffect, useState } from 'react'

import type { KubernetesCluster } from '#/components/KubernetesClusterDialog'
import { KubernetesClusterDialog } from '#/components/KubernetesClusterDialog'
import type { ScmConnection } from '#/components/ScmConnectionDialog'
import { ScmConnectionDialog } from '#/components/ScmConnectionDialog'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent } from '#/components/ui/dialog'
import { apiFetch } from '#/lib/api'
import type { PageResult } from '#/lib/registry-types'
import { useWizardStore } from '#/stores/useWizardStore'

function GitHubIcon() {
  return (
    <svg viewBox="0 0 24 24" className="size-5" fill="currentColor" aria-hidden="true">
      <path d="M12 2C6.477 2 2 6.477 2 12c0 4.418 2.865 8.166 6.839 9.489.5.092.682-.217.682-.482 0-.237-.009-.868-.013-1.703-2.782.604-3.369-1.342-3.369-1.342-.454-1.155-1.11-1.463-1.11-1.463-.908-.62.069-.608.069-.608 1.003.07 1.531 1.03 1.531 1.03.892 1.529 2.341 1.087 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.11-4.555-4.943 0-1.091.39-1.984 1.029-2.683-.103-.253-.446-1.27.098-2.647 0 0 .84-.269 2.75 1.025A9.578 9.578 0 0 1 12 6.836a9.59 9.59 0 0 1 2.504.337c1.909-1.294 2.747-1.025 2.747-1.025.546 1.377.202 2.394.1 2.647.64.699 1.028 1.592 1.028 2.683 0 3.842-2.339 4.687-4.566 4.935.359.309.678.919.678 1.852 0 1.336-.012 2.415-.012 2.743 0 .267.18.578.688.48C19.138 20.163 22 16.418 22 12c0-5.523-4.477-10-10-10z" />
    </svg>
  )
}

interface WizardStep {
  key: string
  title: string
  description: string
  instructions: string[]
  cta: string
  icon: React.ReactNode
}

const STEPS: WizardStep[] = [
  {
    key: 'github',
    title: 'Connect GitHub',
    description: 'Import your repositories to auto-discover services and their dependencies.',
    instructions: [
      'Go to GitHub > Settings > Developer settings > Personal access tokens > Tokens (classic).',
      'Generate a new token with the "repo" and "read:org" scopes.',
      'Copy the token, then click Connect GitHub and enter your organization name and the token.',
    ],
    cta: 'Connect GitHub',
    icon: <GitHubIcon />,
  },
  {
    key: 'azuredevops',
    title: 'Connect Azure DevOps',
    description: 'Sync your repositories to auto-discover services and track changes.',
    instructions: [
      'Go to dev.azure.com > User settings (top-right avatar) > Personal access tokens.',
      'Create a token with Read access scoped to Code and Project and Team.',
      'Click Connect Azure DevOps and enter your organization name and the token.',
    ],
    cta: 'Connect Azure DevOps',
    icon: <Hexagon className="size-5" />,
  },
  {
    key: 'kubernetes',
    title: 'Connect Kubernetes',
    description: 'Sync your K8s cluster to observe real-time service topology and health.',
    instructions: [
      'Kubeconfig method: run "kubectl config view --raw > kubeconfig.yaml" and upload the file.',
      'Service account method: provide your cluster API server URL and a service account token with cluster read permissions.',
      'Click Connect Kubernetes, choose your auth method, and fill in the credentials.',
    ],
    cta: 'Connect Kubernetes',
    icon: <Settings className="size-5" />,
  },
]

const TOTAL_STEPS = 4

export function OnboardingWizard() {
  const [step, setStep] = useState(0)
  const [dismissed, setDismissed] = useState(false)
  const [scmProvider, setScmProvider] = useState<'github' | 'azuredevops' | null>(null)
  const [k8sOpen, setK8sOpen] = useState(false)
  const navigate = useNavigate()
  const { forceShow, closeWizard } = useWizardStore()
  const urlForceShow = new URLSearchParams(window.location.search).get('wizard') === '1'

  const { data: connectionsData, isLoading: loadingConnections } = useQuery({
    queryKey: ['scm-connections'],
    queryFn: () => apiFetch<PageResult<ScmConnection>>('/v1/ingestion/scm-connections'),
  })

  const { data: clustersData, isLoading: loadingClusters } = useQuery({
    queryKey: ['k8s-clusters'],
    queryFn: () => apiFetch<PageResult<KubernetesCluster>>('/v1/ingestion/k8s/clusters'),
  })

  const connections = connectionsData?.items ?? []
  const clusters = clustersData?.items ?? []

  const isLoading = loadingConnections || loadingClusters
  const isOnboarded = connections.length > 0 || clusters.length > 0
  const shouldForce = forceShow || urlForceShow

  useEffect(() => {
    if (shouldForce) {
      setDismissed(false)
      setStep(0)
    }
  }, [shouldForce])

  if (dismissed || isLoading || (isOnboarded && !shouldForce)) return null

  const currentStep = STEPS[step - 1]
  const isDoneStep = step === TOTAL_STEPS

  const githubConnection = connections.find((c) => c.provider === 'github')
  const azureConnection = connections.find((c) => c.provider === 'azuredevops')
  const k8sCluster = clusters[0]

  function isStepConnected(s: number) {
    if (s === 1) return !!githubConnection
    if (s === 2) return !!azureConnection
    if (s === 3) return !!k8sCluster
    return false
  }

  const connected = !isDoneStep && isStepConnected(step)

  function advance() {
    setStep((s) => Math.min(s + 1, TOTAL_STEPS))
  }

  function back() {
    setStep((s) => Math.max(s - 1, 1))
  }

  function dismiss() {
    setDismissed(true)
    closeWizard()
    if (urlForceShow) {
      const params = new URLSearchParams(window.location.search)
      params.delete('wizard')
      const qs = params.toString()
      window.history.replaceState({}, '', window.location.pathname + (qs ? `?${qs}` : ''))
    }
  }

  function handleCta() {
    if (isDoneStep) {
      dismiss()
      navigate({ to: '/dashboard' })
      return
    }
    if (step === 3) {
      setK8sOpen(true)
    } else {
      setScmProvider(step === 1 ? 'github' : 'azuredevops')
    }
  }

  return (
    <>
      <Dialog open onOpenChange={() => {}}>
        <DialogContent
          className="gap-0 p-0 sm:max-w-[480px]"
          showCloseButton={false}
          onInteractOutside={(e) => e.preventDefault()}
        >
          <div className="flex items-center justify-between border-b px-6 py-4">
            <p className="text-sm font-semibold">Get started</p>
            <button
              onClick={dismiss}
              className="rounded-sm text-muted-foreground opacity-70 hover:opacity-100 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              <X className="size-4" />
              <span className="sr-only">Close</span>
            </button>
          </div>

          {step > 0 && (
            <div className="flex gap-1.5 px-6 pt-5">
              {Array.from({ length: TOTAL_STEPS }).map((_, i) => (
                <div
                  key={i}
                  className={`h-1 flex-1 rounded-full transition-colors ${i < step ? 'bg-primary' : 'bg-muted'}`}
                />
              ))}
            </div>
          )}

          <div className="space-y-4 px-6 py-6">
            {step > 0 && !isDoneStep && (
              <p className="text-xs font-semibold uppercase tracking-widest text-muted-foreground">
                Step {step} of {TOTAL_STEPS}
              </p>
            )}

            {step === 0 ? (
              <>
                <div className="flex size-12 items-center justify-center rounded-xl bg-primary/10 text-primary">
                  <PlugZap className="size-6" />
                </div>
                <div className="space-y-2">
                  <h2 className="text-2xl font-bold">Welcome to Cartogra</h2>
                  <p className="text-sm text-muted-foreground">
                    To get the most out of Cartogra, connect your source control and infrastructure providers.
                    This lets us automatically discover your services, map dependencies, and track deployments in real time.
                  </p>
                  <p className="text-sm text-muted-foreground">
                    This setup takes about 2 minutes. You can skip any step and configure connections later in Settings.
                  </p>
                </div>
                <div className="flex items-center gap-3 pt-2">
                  <Button className="flex-1" onClick={() => setStep(1)}>
                    Get started
                  </Button>
                  <Button variant="ghost" onClick={dismiss}>
                    Skip setup
                  </Button>
                </div>
              </>
            ) : isDoneStep ? (
              <>
                <div className="flex size-10 items-center justify-center rounded-xl bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400">
                  <CheckCircle2 className="size-5" />
                </div>
                <div className="space-y-1">
                  <h2 className="text-xl font-bold">You're all set</h2>
                  <p className="text-sm text-muted-foreground">
                    Your connections are configured. Head to the dashboard to explore your services.
                  </p>
                </div>
                <div className="pt-2">
                  <Button className="w-full" onClick={handleCta}>
                    Go to Dashboard
                  </Button>
                </div>
              </>
            ) : (
              <>
                <div className={`flex size-10 items-center justify-center rounded-xl ${connected ? 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' : 'bg-muted text-muted-foreground'}`}>
                  {connected ? <CheckCircle2 className="size-5" /> : currentStep.icon}
                </div>
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <h2 className="text-xl font-bold">{currentStep.title}</h2>
                    {connected && (
                      <span className="flex items-center gap-1 text-xs font-medium text-green-600 dark:text-green-400">
                        <span className="size-1.5 rounded-full bg-green-500" />
                        Connected
                      </span>
                    )}
                  </div>
                  <p className="text-sm text-muted-foreground">{currentStep.description}</p>
                </div>
                <ol className="space-y-2 rounded-lg bg-muted/50 px-4 py-3">
                  {currentStep.instructions.map((instruction, i) => (
                    <li key={i} className="flex gap-2.5 text-xs text-muted-foreground">
                      <span className="mt-px flex size-4 shrink-0 items-center justify-center rounded-full bg-muted text-[10px] font-semibold text-foreground">
                        {i + 1}
                      </span>
                      {instruction}
                    </li>
                  ))}
                </ol>
                <div className="flex items-center gap-2 pt-2">
                  <Button className="flex-1" variant={connected ? 'outline' : 'default'} onClick={handleCta}>
                    {connected ? 'Manage' : currentStep.cta}
                  </Button>
                  <Button variant="outline" onClick={back}>
                    Back
                  </Button>
                  <Button variant="ghost" onClick={advance}>
                    {connected ? 'Next' : 'Skip'}
                  </Button>
                </div>
              </>
            )}
          </div>
        </DialogContent>
      </Dialog>

      {scmProvider && (
        <ScmConnectionDialog
          key={scmProvider}
          open={!!scmProvider}
          onOpenChange={(open) => {
            if (!open) setScmProvider(null)
          }}
          provider={scmProvider}
          connection={scmProvider === 'github' ? githubConnection : azureConnection}
          onSuccess={connected ? undefined : advance}
        />
      )}

      <KubernetesClusterDialog
        open={k8sOpen}
        onOpenChange={(open) => {
          if (!open) setK8sOpen(false)
        }}
        cluster={k8sCluster}
        onSuccess={connected ? undefined : advance}
      />
    </>
  )
}
