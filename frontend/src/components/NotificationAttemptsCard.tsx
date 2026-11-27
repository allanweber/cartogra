import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '#/components/ui/card'
import { Skeleton } from '#/components/ui/skeleton'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'
import type {
  NotificationAttempt,
  NotificationAttemptOutcome,
  NotificationAttemptPage,
  NotificationRule,
} from '#/lib/contract-types'
import { useAuthStore } from '#/stores/useAuthStore'

const OUTCOME_COLOR: Record<NotificationAttemptOutcome, string> = {
  pending: 'text-info',
  sent: 'text-success',
  failed: 'text-critical',
}

interface NotificationAttemptsCardProps {
  rule: NotificationRule
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}

function failureLabel(attempt: NotificationAttempt): string {
  return `${attempt.channel} ${attempt.errorCode ?? 'unknown error'}`
}

function attemptRows(attempts: NotificationAttempt[], label: string) {
  return (
    <ul aria-label={label} className="divide-y rounded-lg border">
      {attempts.map((attempt) => (
        <li
          key={attempt.id}
          className="grid gap-1 px-3 py-2 text-xs sm:grid-cols-[1fr_auto] sm:items-center sm:gap-x-3"
        >
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <Badge
              variant="outline"
              className={`border-current ${OUTCOME_COLOR[attempt.outcome]}`}
            >
              {attempt.outcome}
            </Badge>
            <span className="font-mono">{attempt.channel}</span>
            <span>try {attempt.attempt}</span>
            <span className="break-all font-mono text-muted-foreground">
              {attempt.destinationHint ?? 'Not recorded'}
            </span>
            {attempt.statusCode !== null && <span>HTTP {attempt.statusCode}</span>}
            {attempt.errorCode !== null && (
              <span className="font-mono text-critical">Error: {attempt.errorCode}</span>
            )}
          </div>
          <time dateTime={attempt.createdAt} className="text-muted-foreground sm:justify-self-end">
            {formatTime(attempt.createdAt)}
          </time>
        </li>
      ))}
    </ul>
  )
}

export function NotificationAttemptsCard({ rule }: NotificationAttemptsCardProps) {
  const queryClient = useQueryClient()
  const tenantId = useAuthStore((s) => s.user?.tenantId ?? '')
  const canWrite = useAuthStore(
    (s) =>
      s.user?.roles.some(
        (role) => role === 'ADMIN' || role === 'MEMBER' || role === 'TEAM_OWNER',
      ) ?? false,
  )
  const attemptsKey = ['contracts', 'notification-rules', tenantId, rule.id, 'attempts']
  const [testAttempts, setTestAttempts] = useState<NotificationAttempt[] | null>(null)

  const { data, isLoading, error } = useQuery({
    queryKey: attemptsKey,
    queryFn: () =>
      apiFetch<NotificationAttemptPage>(
        `/v1/contracts/notification-rules/${rule.id}/attempts`,
      ),
  })

  const testMutation = useMutation({
    mutationFn: () =>
      apiMutate<NotificationAttempt[]>(`/v1/contracts/notification-rules/${rule.id}/test`, {}),
    onSuccess: (attempts) => {
      setTestAttempts(attempts)
      void queryClient.invalidateQueries({ queryKey: attemptsKey })
      if (attempts.some((attempt) => attempt.outcome === 'failed')) {
        toast.error('Test finished with failed attempts')
      } else {
        toast.success('Test finished')
      }
    },
  })

  const attempts = data?.items ?? []
  const failedTestAttempts = testAttempts?.filter((attempt) => attempt.outcome === 'failed') ?? []

  return (
    <Card size="sm" className="mt-4">
      <CardHeader>
        <div className="min-w-0">
          <CardTitle className="text-sm">Recent delivery attempts</CardTitle>
          <CardDescription className="text-xs">
            The latest 50 attempts, newest first. The backend masks each destination.
          </CardDescription>
        </div>
        {canWrite && (
          <CardAction>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={!rule.enabled || testMutation.isPending}
              onClick={() => testMutation.mutate()}
            >
              {testMutation.isPending ? 'Testing…' : 'Test rule'}
            </Button>
          </CardAction>
        )}
      </CardHeader>
      <CardContent className="space-y-3">
        {!rule.enabled && canWrite && (
          <p className="text-xs text-muted-foreground">Enable the rule to run a test.</p>
        )}

        {isLoading && <Skeleton className="h-16 w-full rounded-lg" />}

        {(error || testMutation.error) && (
          <Alert variant="destructive">
            <AlertDescription>
              {(error ?? testMutation.error)?.message}
              {(error ?? testMutation.error) instanceof ApiError &&
                ` (trace: ${((error ?? testMutation.error) as ApiError).traceId})`}
            </AlertDescription>
          </Alert>
        )}

        {data && attempts.length === 0 && <p className="text-xs text-muted-foreground">No attempts yet.</p>}
        {attempts.length > 0 && attemptRows(attempts, 'Delivery attempts')}

        {testAttempts !== null && (
          <div className="space-y-2 border-t pt-3">
            <p className="text-xs font-medium">Test outcomes</p>
            {failedTestAttempts.length > 0 && (
              <Alert variant="destructive">
                <AlertDescription>
                  Delivery failed: {failedTestAttempts.map(failureLabel).join(', ')}.
                </AlertDescription>
              </Alert>
            )}
            {testAttempts.length === 0 ? (
              <p className="text-xs text-muted-foreground">The test sent no messages.</p>
            ) : (
              attemptRows(testAttempts, 'Test outcomes')
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
