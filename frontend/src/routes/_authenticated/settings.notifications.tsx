import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createFileRoute } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { toast } from 'sonner'

import {
  NOTIFICATION_RULES_QUERY_PREFIX,
  NotificationRuleCard,
  parseEmailAddresses,
} from '#/components/NotificationRuleCard'
import { SettingsTabsLayout } from '#/components/SettingsTabsLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { Label } from '#/components/ui/label'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'
import type {
  NotificationRule,
  NotificationRulePage,
  NotificationRuleRequest,
} from '#/lib/contract-types'
import { useAuthStore } from '#/stores/useAuthStore'

export const Route = createFileRoute('/_authenticated/settings/notifications')({
  component: NotificationsPage,
})

function NotificationsPage() {
  const queryClient = useQueryClient()
  const tenantId = useAuthStore((s) => s.user?.tenantId ?? '')
  const canWrite = useAuthStore(
    (s) =>
      s.user?.roles.some(
        (role) => role === 'ADMIN' || role === 'MEMBER' || role === 'TEAM_OWNER',
      ) ?? false,
  )
  const rulesKey = [...NOTIFICATION_RULES_QUERY_PREFIX, tenantId]

  const { data, isLoading, error } = useQuery({
    queryKey: rulesKey,
    queryFn: () =>
      apiFetch<NotificationRulePage>('/v1/contracts/notification-rules?limit=20&offset=0'),
  })

  const [creating, setCreating] = useState(false)
  const [serviceId, setServiceId] = useState('')
  const [slackUrl, setSlackUrl] = useState('')
  const [teamsUrl, setTeamsUrl] = useState('')
  const [emails, setEmails] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (body: NotificationRuleRequest) =>
      apiMutate<NotificationRule>('/v1/contracts/notification-rules', body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: rulesKey })
      resetCreateForm()
      toast.success('Notification rule created')
    },
  })

  function resetCreateForm() {
    setCreating(false)
    setServiceId('')
    setSlackUrl('')
    setTeamsUrl('')
    setEmails('')
    setFormError(null)
    createMutation.reset()
  }

  function createRule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const addressList = parseEmailAddresses(emails)
    if (!slackUrl.trim() && !teamsUrl.trim() && addressList.length === 0) {
      setFormError('Enter at least one destination.')
      return
    }
    if (addressList.length > 20) {
      setFormError('Use 20 email addresses or fewer.')
      return
    }
    setFormError(null)
    const body: NotificationRuleRequest = {
      slackWebhookUrl: slackUrl.trim() || null,
      teamsWebhookUrl: teamsUrl.trim() || null,
      emailAddresses: addressList,
    }
    if (serviceId.trim()) body.serviceId = serviceId.trim()
    createMutation.mutate(body)
  }

  const rules = data?.items ?? []

  return (
    <SettingsTabsLayout>
      <div className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold">Notifications</h2>
          <p className="text-xs text-muted-foreground">
            Manage the destinations for breaking contract checks. The backend masks each destination.
          </p>
        </div>

        <Card>
          <CardHeader className="flex flex-row items-center justify-between gap-2">
            <div>
              <CardTitle className="text-sm">Notification rules</CardTitle>
              <CardDescription className="text-xs">
                One rule for the tenant and one rule for each Service can send at the same time.
              </CardDescription>
            </div>
            {canWrite && !creating && (
              <Button size="sm" className="gap-1.5" onClick={() => setCreating(true)}>
                <Plus className="size-3.5" aria-hidden="true" />
                Create rule
              </Button>
            )}
          </CardHeader>
          <CardContent className="space-y-3">
            {isLoading && <Skeleton className="h-24 w-full rounded-lg" />}

            {error && (
              <Alert variant="destructive">
                <AlertDescription>
                  {error.message}
                  {error instanceof ApiError && ` (trace: ${error.traceId})`}
                </AlertDescription>
              </Alert>
            )}

            {data && rules.length === 0 && (
              <p className="text-sm text-muted-foreground">No notification rules yet.</p>
            )}

            {creating && canWrite && (
              <form className="space-y-3 rounded-lg border p-4" onSubmit={createRule}>
                <p className="text-xs text-muted-foreground">
                  A new rule is enabled at once. Enter each destination that the rule must use.
                </p>
                {(formError || createMutation.error) && (
                  <Alert variant="destructive">
                    <AlertDescription>
                      {formError ?? createMutation.error?.message}
                      {!formError && createMutation.error instanceof ApiError &&
                        ` (trace: ${createMutation.error.traceId})`}
                    </AlertDescription>
                  </Alert>
                )}
                <div className="space-y-1">
                  <Label htmlFor="rule-service-id">Service ID (optional)</Label>
                  <Input
                    id="rule-service-id"
                    value={serviceId}
                    onChange={(event) => setServiceId(event.target.value)}
                    placeholder="Leave empty for the tenant-wide rule"
                    className="h-8 font-mono text-xs"
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="rule-slack-url">Slack webhook URL</Label>
                  <Input
                    id="rule-slack-url"
                    value={slackUrl}
                    onChange={(event) => setSlackUrl(event.target.value)}
                    className="h-8 font-mono text-xs"
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="rule-teams-url">Teams webhook URL</Label>
                  <Input
                    id="rule-teams-url"
                    value={teamsUrl}
                    onChange={(event) => setTeamsUrl(event.target.value)}
                    className="h-8 font-mono text-xs"
                  />
                </div>
                <div className="space-y-1">
                  <Label htmlFor="rule-emails">Email addresses</Label>
                  <Input
                    id="rule-emails"
                    value={emails}
                    onChange={(event) => setEmails(event.target.value)}
                    placeholder="one@example.com, two@example.com"
                    className="h-8 font-mono text-xs"
                  />
                </div>
                <div className="flex gap-2">
                  <Button type="submit" size="sm" disabled={createMutation.isPending}>
                    {createMutation.isPending ? 'Creating…' : 'Create rule'}
                  </Button>
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={resetCreateForm}
                  >
                    Cancel
                  </Button>
                </div>
              </form>
            )}
          </CardContent>
        </Card>

        {rules.map((rule) => (
          <NotificationRuleCard key={rule.id} rule={rule} />
        ))}
      </div>
    </SettingsTabsLayout>
  )
}
