import { createFileRoute, Link, useNavigate, useSearch } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { z } from 'zod'

import { AuthFormShell } from '#/components/AuthFormShell'
import { ApiError, apiFetch } from '#/lib/api'

const searchSchema = z.object({
  code: z.string().optional(),
  state: z.string().optional(),
  error: z.string().optional(),
})

export const Route = createFileRoute('/oauth-handoff')({
  validateSearch: searchSchema,
  component: OAuthHandoffPage,
})

function OAuthHandoffPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { code, error: oauthError } = useSearch({ from: '/oauth-handoff' })
  const [error, setError] = useState<string | null>(oauthError ?? null)
  const [traceId, setTraceId] = useState<string | null>(null)

  useEffect(() => {
    if (oauthError || !code) {
      setError(oauthError ?? 'OAuth flow did not return an authorization code.')
      return
    }

    apiFetch<unknown>(`/auth/oauth/callback?code=${encodeURIComponent(code)}`)
      .then(() => {
        queryClient.removeQueries({ queryKey: ['session'] })
        navigate({ to: '/dashboard' })
      })
      .catch((err) => {
        if (err instanceof ApiError) {
          setError(err.message)
          setTraceId(err.traceId)
        } else {
          setError('Authentication failed. Please try again.')
        }
      })
  }, []) // runs once on mount

  return (
    <AuthFormShell>
      <div className="text-center">
        {error ? (
          <>
            <p className="text-sm font-medium text-destructive">{error}</p>
            {traceId && (
              <p className="mt-1 font-mono text-xs text-muted-foreground">(trace: {traceId})</p>
            )}
            <Link
              to="/login"
              className="mt-4 inline-block text-sm font-medium text-primary underline-offset-3 hover:underline"
            >
              Back to sign in
            </Link>
          </>
        ) : (
          <>
            <Loader2 className="mx-auto mb-3 size-8 animate-spin text-primary" aria-hidden="true" />
            <p className="text-sm text-muted-foreground">Completing sign-in…</p>
          </>
        )}
      </div>
    </AuthFormShell>
  )
}
