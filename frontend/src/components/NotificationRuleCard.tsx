import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import type { FormEvent } from 'react'
import { toast } from 'sonner'

import { ConfirmDialog } from '#/components/ConfirmDialog'
import { NotificationAttemptsCard } from '#/components/NotificationAttemptsCard'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { Label } from '#/components/ui/label'
import { ApiError, apiMutate } from '#/lib/api'
import type { NotificationRule, NotificationRuleRequest } from '#/lib/contract-types'
import { useAuthStore } from '#/stores/useAuthStore'

export const NOTIFICATION_RULES_QUERY_PREFIX = ['contracts', 'notification-rules'] as const

export function parseEmailAddresses(value: string): string[] {
  return value
    .split(/[,\n]/)
    .map((address) => address.trim())
    .filter((address) => address.length > 0)
}

interface NotificationRuleCardProps {
  rule: NotificationRule
}

export function NotificationRuleCard({ rule }: NotificationRuleCardProps) {
  const queryClient = useQueryClient()
  const tenantId = useAuthStore((s) => s.user?.tenantId ?? '')
  const canWrite = useAuthStore(
    (s) =>
      s.user?.roles.some(
        (role) => role === 'ADMIN' || role === 'MEMBER' || role === 'TEAM_OWNER',
      ) ?? false,
  )
  const [editing, setEditing] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [slackUrl, setSlackUrl] = useState('')
  const [teamsUrl, setTeamsUrl] = useState('')
  const [emails, setEmails] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const rulesKey = [...NOTIFICATION_RULES_QUERY_PREFIX, tenantId]

  function refreshRules() {
    void queryClient.invalidateQueries({ queryKey: rulesKey })
  }

  const saveMutation = useMutation({
    mutationFn: (body: NotificationRuleRequest) =>
      apiMutate<NotificationRule>(
        `/v1/contracts/notification-rules/${rule.id}`,
        body,
        'PUT',
      ),
    onSuccess: () => {
      refreshRules()
      setEditing(false)
      toast.success('Notification rule saved')
    },
  })

  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) =>
      apiMutate<NotificationRule>(
        `/v1/contracts/notification-rules/${rule.id}`,
        { enabled },
        'PATCH',
      ),
    onSuccess: () => {
      refreshRules()
      toast.success(rule.enabled ? 'Rule paused' : 'Rule enabled')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: () =>
      apiMutate<void>(`/v1/contracts/notification-rules/${rule.id}`, undefined, 'DELETE'),
    onSuccess: () => {
      refreshRules()
      setDeleteOpen(false)
      toast.success('Notification rule deleted')
    },
  })

  function startEditing() {
    setSlackUrl('')
    setTeamsUrl('')
    setEmails('')
    setFormError(null)
    saveMutation.reset()
    setEditing(true)
  }

  function saveRule(event: FormEvent<HTMLFormElement>) {
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
    saveMutation.mutate({
      slackWebhookUrl: slackUrl.trim() || null,
      teamsWebhookUrl: teamsUrl.trim() || null,
      emailAddresses: addressList,
    })
  }

  return (
    <Card>
      <CardHeader>
        <div className="min-w-0">
          <CardTitle className="text-sm">
            {rule.serviceId ? 'Service rule' : 'Tenant-wide rule'}
          </CardTitle>
          <p className="mt-1 break-all font-mono text-xs text-muted-foreground">
            {rule.serviceId ?? 'All services'}
          </p>
        </div>
        <CardAction>
          <Badge
            variant="outline"
            className={`border-current ${rule.enabled ? 'text-success' : 'text-warning'}`}
          >
            {rule.enabled ? 'enabled' : 'paused'}
          </Badge>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="grid gap-3 text-sm sm:grid-cols-2">
          <div>
            <p className="text-xs text-muted-foreground">Slack</p>
            <p className="break-all font-mono text-xs">{rule.slackWebhookUrl ?? 'Not set'}</p>
          </div>
          <div>
            <p className="text-xs text-muted-foreground">Teams</p>
            <p className="break-all font-mono text-xs">{rule.teamsWebhookUrl ?? 'Not set'}</p>
          </div>
          <div className="sm:col-span-2">
            <p className="text-xs text-muted-foreground">Email addresses</p>
            {rule.emailAddresses.length === 0 ? (
              <p className="text-xs text-muted-foreground">None</p>
            ) : (
              <ul aria-label="Masked email addresses" className="space-y-0.5">
                {rule.emailAddresses.map((address) => (
                  <li key={address} className="break-all font-mono text-xs">
                    {address}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>

        {canWrite && (
          <div className="flex flex-wrap gap-2 border-t pt-3">
            <Button type="button" size="sm" variant="outline" onClick={startEditing}>
              Edit destinations
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={toggleMutation.isPending}
              onClick={() => toggleMutation.mutate(!rule.enabled)}
            >
              {rule.enabled ? 'Pause' : 'Enable'}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="destructive"
              onClick={() => setDeleteOpen(true)}
            >
              Delete
            </Button>
          </div>
        )}

        {editing && canWrite && (
          <form className="space-y-3 border-t pt-4" onSubmit={saveRule}>
            <p className="text-xs text-muted-foreground">
              Saving replaces the saved destinations. Enter each value again to keep it.
            </p>
            {(formError || saveMutation.error) && (
              <Alert variant="destructive">
                <AlertDescription>
                  {formError ?? saveMutation.error?.message}
                  {!formError && saveMutation.error instanceof ApiError &&
                    ` (trace: ${saveMutation.error.traceId})`}
                </AlertDescription>
              </Alert>
            )}
            <div className="space-y-1">
              <Label htmlFor={`slack-${rule.id}`}>Slack webhook URL</Label>
              <Input
                id={`slack-${rule.id}`}
                value={slackUrl}
                onChange={(event) => setSlackUrl(event.target.value)}
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor={`teams-${rule.id}`}>Teams webhook URL</Label>
              <Input
                id={`teams-${rule.id}`}
                value={teamsUrl}
                onChange={(event) => setTeamsUrl(event.target.value)}
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="space-y-1">
              <Label htmlFor={`emails-${rule.id}`}>Email addresses</Label>
              <Input
                id={`emails-${rule.id}`}
                value={emails}
                onChange={(event) => setEmails(event.target.value)}
                placeholder="one@example.com, two@example.com"
                className="h-8 font-mono text-xs"
              />
            </div>
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={saveMutation.isPending}>
                {saveMutation.isPending ? 'Saving…' : 'Save rule'}
              </Button>
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={() => setEditing(false)}
              >
                Cancel
              </Button>
            </div>
          </form>
        )}

        <NotificationAttemptsCard rule={rule} />

        {canWrite && (
          <ConfirmDialog
            open={deleteOpen}
            onOpenChange={(open) => {
              if (!open) {
                setDeleteOpen(false)
                deleteMutation.reset()
              }
            }}
            title="Delete notification rule"
            description="Delete this rule? Delivery stops at once."
            confirmLabel="Delete"
            pendingLabel="Deleting…"
            isPending={deleteMutation.isPending}
            error={deleteMutation.error}
            onConfirm={() => deleteMutation.mutate()}
          />
        )}
      </CardContent>
    </Card>
  )
}
