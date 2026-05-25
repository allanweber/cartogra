import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { GitBranch } from 'lucide-react'
import { useState } from 'react'

import { AuthFormShell } from '#/components/AuthFormShell'
import { Button } from '#/components/ui/button'
import { Input } from '#/components/ui/input'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { ApiError, apiMutate } from '#/lib/api'

export const Route = createFileRoute('/forgot-password')({
  component: ForgotPasswordPage,
})

function ForgotPasswordPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [traceId, setTraceId] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)

    if (!email) {
      setError('Please enter your email address.')
      return
    }

    setLoading(true)
    try {
      await apiMutate('/auth/forgot-password', { email })
      navigate({ to: '/reset-password', search: { email } })
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
        <div className="mx-auto mb-4 flex size-12 items-center justify-center rounded-full bg-primary/10">
          <GitBranch className="size-5 text-primary" />
        </div>
        <h2 className="text-base font-semibold">Reset your password</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">
          Enter your email and we'll send you a 6-digit reset code.
        </p>
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
          {loading ? 'Sending…' : 'Send reset code'}
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
