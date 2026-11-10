import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect } from 'react'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { ApiError, startGuestSession } from '#/lib/api'

export const Route = createFileRoute('/demo')({
  component: DemoPage,
})

function demoErrorMessage(err: unknown): string {
  if (err instanceof ApiError && err.code === 'RATE_LIMITED') {
    return 'Too many demo sessions started from your network. Try again in a minute.'
  }
  if (err instanceof ApiError && err.code === 'NOT_FOUND') {
    return 'The demo is not available on this instance.'
  }
  return 'Could not start the demo. Please try again.'
}

function DemoPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const guestSession = useMutation({
    mutationFn: startGuestSession,
    onSuccess: () => {
      queryClient.clear()
      navigate({ to: '/dashboard' })
    },
  })
  const { mutate } = guestSession

  useEffect(() => {
    mutate()
  }, [mutate])

  const error = guestSession.error

  return (
    <AuthFormShell>
      <div className="text-center">
        {error ? (
          <>
            <Alert variant="destructive">
              <AlertDescription>
                {demoErrorMessage(error)}
                {error instanceof ApiError && error.traceId && (
                  <span className="mt-1 block font-mono text-xs">(trace: {error.traceId})</span>
                )}
              </AlertDescription>
            </Alert>
            <Link to="/login" className="mt-4 inline-block text-sm font-medium text-primary underline-offset-3 hover:underline">
              Back to sign in
            </Link>
          </>
        ) : (
          <p className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
            Opening the demo…
          </p>
        )}
      </div>
    </AuthFormShell>
  )
}
