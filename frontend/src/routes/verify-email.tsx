import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { Mail, CheckCircle } from 'lucide-react'
import { useState } from 'react'
import { z } from 'zod'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { ApiError, apiMutate } from '#/lib/api'

export const Route = createFileRoute('/verify-email')({
  validateSearch: z.object({
    email: z.string().optional(),
  }),
  component: VerifyEmailPage,
})

function VerifyEmailPage() {
  const navigate = useNavigate()
  const { email: emailParam } = Route.useSearch()
  const [token, setToken] = useState('')
  const [loading, setLoading] = useState(false)
  const [resending, setResending] = useState(false)
  const [resent, setResent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [traceId, setTraceId] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)

    if (!emailParam) {
      setError('Email is missing. Please go back to registration.')
      return
    }
    if (!token || token.length !== 6) {
      setError('Please enter the 6-digit code from your email.')
      return
    }

    setLoading(true)
    try {
      await apiMutate('/auth/verify', { email: emailParam, token })
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

  async function handleResend() {
    if (resending || resent || !emailParam) return
    setError(null)
    setTraceId(null)
    setResending(true)
    try {
      await apiMutate('/auth/resend-verification', { email: emailParam })
      setResent(true)
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setTraceId(err.traceId)
      } else {
        setError('Failed to resend email.')
      }
    } finally {
      setResending(false)
    }
  }

  return (
    <AuthFormShell>
      <div className="mb-5 text-center">
        <div className="mx-auto mb-4 flex size-14 items-center justify-center rounded-full bg-primary/10">
          <Mail className="size-7 text-primary" aria-hidden="true" />
        </div>
        <h2 className="text-base font-semibold">Verify your email</h2>
        <p className="mt-1.5 text-sm text-muted-foreground">
          We sent a 6-digit code to{' '}
          <span className="font-medium text-foreground">
            {emailParam ?? 'your email address'}
          </span>
          . Enter it below to activate your account.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-1.5">
          <label htmlFor="token" className="text-sm font-medium">Verification code</label>
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

        {resent && (
          <div className="flex items-center justify-center gap-1.5 text-sm text-[oklch(0.38_0.14_145)] dark:text-[oklch(0.72_0.16_145)]">
            <CheckCircle className="size-4" aria-hidden="true" />
            Code resent successfully
          </div>
        )}

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
          {loading ? 'Verifying…' : 'Verify email'}
        </Button>

        <Button
          type="button"
          variant="outline"
          className="h-10 w-full text-sm"
          onClick={handleResend}
          disabled={resending || resent || !emailParam}
        >
          {resending ? 'Resending…' : resent ? 'Code sent' : 'Resend code'}
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
