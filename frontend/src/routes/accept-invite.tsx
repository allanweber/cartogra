import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { z } from 'zod'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { ApiError, apiMutate } from '#/lib/api'
import { writeSessionCookie } from '#/lib/session'
import { useAuthStore } from '#/stores/useAuthStore'

export const Route = createFileRoute('/accept-invite')({
  validateSearch: z.object({
    token: z.string().optional(),
  }),
  component: AcceptInvitePage,
})

interface TokenPayload {
  sub: string
  tid: string
  email: string
  name?: string | null
  auth_provider: string
  roles: string[]
  exp: number
}

function decodeJwtPayload(token: string): TokenPayload {
  const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  return JSON.parse(atob(base64)) as TokenPayload
}

function AcceptInvitePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const hydrateWith = useAuthStore((s) => s.hydrateWith)
  const { token: tokenParam } = Route.useSearch()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(
    tokenParam ? null : 'This invite link is missing its token.',
  )
  const [traceId, setTraceId] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)

    if (!tokenParam) {
      setError('This invite link is missing its token.')
      return
    }
    if (!password || !confirm) {
      setError('Please fill in all fields.')
      return
    }
    if (password !== confirm) {
      setError('Passwords do not match.')
      return
    }

    setLoading(true)
    try {
      const result = await apiMutate<{ accessToken: string; expiresIn: number; refreshToken: string }>(
        '/auth/accept-invite',
        { token: tokenParam, password },
      )
      const claims = decodeJwtPayload(result.accessToken)
      const user = { id: claims.sub, email: claims.email, name: claims.name ?? null, authProvider: claims.auth_provider, tenantId: claims.tid, roles: claims.roles }
      writeSessionCookie(user, result.expiresIn)
      hydrateWith(user, claims.exp)
      queryClient.removeQueries({ queryKey: ['session'] })
      navigate({ to: '/dashboard' })
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setTraceId(err.traceId)
      } else {
        setError('An unexpected error occurred.')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthFormShell>
      <div className="mb-5">
        <h2 className="text-base font-semibold">Accept your invite</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Set a password to join your team on Cartogra.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1.5">
          <label htmlFor="password" className="text-sm font-medium">Password</label>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              className="h-10 pr-10 text-sm"
            />
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={() => setShowPassword((s) => !s)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </Button>
          </div>
        </div>

        <div className="space-y-1.5">
          <label htmlFor="confirm" className="text-sm font-medium">Confirm password</label>
          <Input
            id="confirm"
            type={showPassword ? 'text' : 'password'}
            placeholder="••••••••"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            autoComplete="new-password"
            className="h-10 text-sm"
          />
        </div>

        {error && (
          <Alert variant="destructive" role="alert">
            <AlertDescription>
              {error}
              {traceId && (
                <span className="ml-1 font-mono text-xs opacity-70">(trace: {traceId})</span>
              )}
            </AlertDescription>
          </Alert>
        )}

        <Button type="submit" disabled={loading || !tokenParam} className="h-10 w-full">
          {loading ? 'Joining…' : 'Accept invite'}
        </Button>
      </form>

      <div className="mt-4 text-center">
        <Link
          to="/login"
          className="text-xs text-muted-foreground underline-offset-3 hover:text-foreground hover:underline"
        >
          Back to sign in
        </Link>
      </div>
    </AuthFormShell>
  )
}
