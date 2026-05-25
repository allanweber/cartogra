import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { z } from 'zod'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { ApiError, apiMutate } from '#/lib/api'
import { writeSessionCookie } from '#/lib/session'
import { useAuthStore } from '#/stores/useAuthStore'

export const Route = createFileRoute('/login')({
  validateSearch: z.object({
    redirect: z.string().optional(),
    error: z.string().optional(),
  }),
  component: LoginPage,
})

interface TokenPayload {
  sub: string
  tid: string
  email: string
  roles: string[]
}

function decodeJwtPayload(token: string): TokenPayload {
  const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  return JSON.parse(atob(base64)) as TokenPayload
}

function LoginPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const hydrateWith = useAuthStore((s) => s.hydrateWith)
  const { redirect: redirectTo, error: oauthError } = Route.useSearch()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(
    oauthError === 'oauth_failed' ? 'Sign-in with OAuth failed. Please try again or use email.' : null,
  )
  const [traceId, setTraceId] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)

    if (!email || !password) {
      setError('Please fill in all fields.')
      return
    }

    setLoading(true)
    try {
      const result = await apiMutate<{ accessToken: string; expiresIn: number; refreshToken: string }>(
        '/auth/login',
        { email, password },
      )
      const claims = decodeJwtPayload(result.accessToken)
      const user = { id: claims.sub, email: claims.email, tenantId: claims.tid, roles: claims.roles }
      writeSessionCookie(user, result.expiresIn)
      hydrateWith(user)
      queryClient.removeQueries({ queryKey: ['session'] })
      navigate({ to: redirectTo ?? '/dashboard' })
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

  function handleOAuth(provider: 'github' | 'google') {
    const apiBase = import.meta.env.VITE_API_BASE_URL ?? ''
    window.location.href = `${apiBase}/api/auth/oauth/${provider}/start`
  }

  return (
    <AuthFormShell>
      <div className="mb-5">
        <h2 className="text-base font-semibold">Sign in to your workspace</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">Enter your credentials to continue</p>
      </div>

      <div className="space-y-2">
        <Button
          type="button"
          variant="outline"
          className="h-10 w-full text-sm"
          onClick={() => handleOAuth('github')}
        >
          <svg role="img" aria-label="GitHub" className="mr-2 size-4" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 0C5.37 0 0 5.37 0 12c0 5.3 3.438 9.8 8.205 11.387.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61-.546-1.387-1.333-1.756-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23A11.509 11.509 0 0 1 12 5.803c1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 2.222 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.573C20.565 21.795 24 17.295 24 12c0-6.63-5.37-12-12-12z" />
          </svg>
          Continue with GitHub
        </Button>
        <Button
          type="button"
          variant="outline"
          className="h-10 w-full text-sm"
          onClick={() => handleOAuth('google')}
        >
          <svg role="img" aria-label="Google" className="mr-2 size-4" viewBox="0 0 24 24" fill="currentColor">
            <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/>
            <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
            <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/>
            <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
          </svg>
          Continue with Google
        </Button>
      </div>

      <div className="relative my-5">
        <div className="absolute inset-0 flex items-center">
          <span className="w-full border-t border-border" />
        </div>
        <div className="relative flex justify-center text-xs uppercase">
          <span className="bg-card px-2 text-muted-foreground">or sign in with email</span>
        </div>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1.5">
          <label htmlFor="email" className="text-sm font-medium">Email</label>
          <Input
            id="email"
            type="email"
            placeholder="you@company.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            className="h-10 text-sm"
          />
        </div>

        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <label htmlFor="password" className="text-sm font-medium">Password</label>
            <Link
              to="/forgot-password"
              className="text-xs text-muted-foreground underline-offset-3 hover:text-foreground hover:underline"
            >
              Forgot password?
            </Link>
          </div>
          <div className="relative">
            <Input
              id="password"
              type={showPassword ? 'text' : 'password'}
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              className="h-10 pr-10 text-sm"
            />
            <button
              type="button"
              onClick={() => setShowPassword((s) => !s)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
            >
              {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
            </button>
          </div>
        </div>

        {error && (
          <p className="text-sm text-destructive">
            {error}
            {traceId && (
              <span className="ml-1 font-mono text-xs text-muted-foreground">(trace: {traceId})</span>
            )}
          </p>
        )}

        <Button type="submit" disabled={loading} className="h-10 w-full">
          {loading ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>

      <div className="mt-4 text-center">
        <p className="text-xs text-muted-foreground">
          Don&apos;t have an account?{' '}
          <Link to="/register" className="font-medium text-primary underline-offset-3 hover:underline">
            Create account
          </Link>
        </p>
      </div>
    </AuthFormShell>
  )
}
