import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { z } from 'zod'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { ApiError, apiMutate } from '#/lib/api'

export const Route = createFileRoute('/reset-password')({
  validateSearch: z.object({
    email: z.string().optional(),
  }),
  component: ResetPasswordPage,
})

function ResetPasswordPage() {
  const navigate = useNavigate()
  const { email: emailParam } = Route.useSearch()
  const [token, setToken] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [traceId, setTraceId] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)

    if (!token || !password || !confirm) {
      setError('Please fill in all fields.')
      return
    }
    if (password !== confirm) {
      setError('Passwords do not match.')
      return
    }

    setLoading(true)
    try {
      await apiMutate('/auth/reset-password', { token, newPassword: password })
      navigate({ to: '/login' })
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
        <h2 className="text-base font-semibold">Set a new password</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          {emailParam
            ? <>Enter the 6-digit code sent to <span className="font-medium">{emailParam}</span>.</>
            : 'Enter the 6-digit code from your email.'}
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1.5">
          <label htmlFor="token" className="text-sm font-medium">Reset code</label>
          <Input
            id="token"
            type="text"
            inputMode="numeric"
            placeholder="000000"
            maxLength={6}
            value={token}
            onChange={(e) => setToken(e.target.value.replace(/\D/g, ''))}
            autoComplete="one-time-code"
            className="h-10 text-center text-sm tracking-widest"
          />
        </div>

        <div className="space-y-1.5">
          <label htmlFor="password" className="text-sm font-medium">New password</label>
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

        <Button type="submit" disabled={loading} className="h-10 w-full">
          {loading ? 'Updating…' : 'Set new password'}
        </Button>
      </form>

      <div className="mt-4 text-center">
        <Link
          to="/forgot-password"
          className="text-xs text-muted-foreground underline-offset-3 hover:text-foreground hover:underline"
        >
          Resend code
        </Link>
        <span className="mx-2 text-xs text-muted-foreground">·</span>
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
