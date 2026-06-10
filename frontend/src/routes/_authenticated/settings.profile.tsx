import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { KeyRound } from 'lucide-react'
import { useState } from 'react'

import { AppLayout } from '#/components/AppLayout'
import { Alert, AlertDescription } from '#/components/ui/alert'
import { Badge } from '#/components/ui/badge'
import { Button } from '#/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '#/components/ui/card'
import { Input } from '#/components/ui/input'
import { ApiError, apiMutate } from '#/lib/api'
import { clearSessionCookie } from '#/lib/session'
import { useAuthStore } from '#/stores/useAuthStore'
import type { AuthUser } from '#/stores/useAuthStore'

export const Route = createFileRoute('/_authenticated/settings/profile')({
  component: ProfilePage,
})

function resolveHighestRole(roles: string[]): string {
  const normalized = roles.map((r) => r.toLowerCase())
  if (normalized.includes('admin')) return 'Admin'
  return 'User'
}

function ProfilePage() {
  const user = useAuthStore((s) => s.user)
  const hydrateWith = useAuthStore((s) => s.hydrateWith)
  const clearAuth = useAuthStore((s) => s.clearAuth)
  const navigate = useNavigate()

  const [name, setName] = useState(user?.name ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [traceId, setTraceId] = useState<string | null>(null)

  const [pwExpanded, setPwExpanded] = useState(false)
  const [sendingCode, setSendingCode] = useState(false)
  const [pwError, setPwError] = useState<string | null>(null)
  const [pwTraceId, setPwTraceId] = useState<string | null>(null)

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setTraceId(null)
    setSaving(true)
    const originalEmail = user?.email
    try {
      const payload = user?.authProvider === 'local' ? { name, email } : { name }
      const updated = await apiMutate<AuthUser>('/auth/userinfo', payload, 'PUT')
      hydrateWith(updated)
      if (updated.email !== originalEmail) {
        navigate({ to: '/verify-email', search: { email: updated.email } })
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setTraceId(err.traceId)
      } else {
        setError('An unexpected error occurred.')
      }
    } finally {
      setSaving(false)
    }
  }

  async function handleSendCode() {
    if (!user?.email) return
    setPwError(null)
    setPwTraceId(null)
    setSendingCode(true)
    try {
      await apiMutate('/auth/forgot-password', { email: user.email })
      // Sign out then hand off to the reset-password flow
      try { await apiMutate('/auth/logout', {}) } catch { /* best-effort */ }
      clearSessionCookie()
      clearAuth()
      navigate({ to: '/reset-password', search: { email: user.email } })
    } catch (err) {
      if (err instanceof ApiError) {
        setPwError(err.message)
        setPwTraceId(err.traceId)
      } else {
        setPwError('An unexpected error occurred.')
      }
      setSendingCode(false)
    }
  }

  return (
    <AppLayout title="Profile" description="Your identity and credentials">
      <div className="mx-auto max-w-2xl space-y-4">
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">Identity</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex justify-end">
              <Badge className="capitalize">{user ? resolveHighestRole(user.roles) : 'User'}</Badge>
            </div>

            <form onSubmit={handleSave} className="space-y-3">
              <div className="space-y-1.5">
                <label htmlFor="display-name" className="text-xs font-medium text-muted-foreground">
                  Display name
                </label>
                <Input
                  id="display-name"
                  type="text"
                  placeholder="Your name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="h-9 text-sm"
                />
              </div>

              <div className="space-y-1.5">
                <label htmlFor="email" className="text-xs font-medium text-muted-foreground">
                  Email
                </label>
                <Input
                  id="email"
                  type="email"
                  placeholder="you@company.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  disabled={user?.authProvider !== 'local'}
                  className="h-9 text-sm"
                />
                {user?.authProvider !== 'local' && (
                  <p className="text-xs text-muted-foreground">
                    Email is managed by your {user?.authProvider} account.
                  </p>
                )}
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

              <Button type="submit" disabled={saving} size="sm">
                {saving ? 'Saving…' : 'Save'}
              </Button>
            </form>
          </CardContent>
        </Card>

        {user?.authProvider === 'local' && (
          <Card>
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm">Password</CardTitle>
                {!pwExpanded && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setPwExpanded(true)}
                    className="gap-1.5 text-xs"
                  >
                    <KeyRound className="size-3.5" />
                    Change password
                  </Button>
                )}
              </div>
            </CardHeader>

            {pwExpanded && (
              <CardContent className="space-y-3">
                <p className="text-xs text-muted-foreground">
                  A 6-digit code will be sent to <span className="font-medium text-foreground">{user.email}</span>. You'll be signed out and redirected to enter the code and set a new password.
                </p>

                {pwError && (
                  <Alert variant="destructive" role="alert">
                    <AlertDescription>
                      {pwError}
                      {pwTraceId && (
                        <span className="ml-1 font-mono text-xs opacity-70">(trace: {pwTraceId})</span>
                      )}
                    </AlertDescription>
                  </Alert>
                )}

                <div className="flex gap-2">
                  <Button size="sm" onClick={handleSendCode} disabled={sendingCode}>
                    {sendingCode ? 'Sending…' : 'Send code to my email'}
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => { setPwExpanded(false); setPwError(null); setPwTraceId(null) }}
                    disabled={sendingCode}
                  >
                    Cancel
                  </Button>
                </div>
              </CardContent>
            )}
          </Card>
        )}
      </div>
    </AppLayout>
  )
}
