import { useState } from 'react'

import { Alert, AlertDescription } from '#/components/ui/alert'
import { Button } from '#/components/ui/button'
import { Checkbox } from '#/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '#/components/ui/dialog'
import { Input } from '#/components/ui/input'
import { Label } from '#/components/ui/label'
import { useCreateApiKey } from '#/hooks/useApiKeys'
import { ApiError } from '#/lib/api'
import { endOfLocalDayIso, toLocalDateString } from '#/lib/local-date'
import type { CreatedApiKey } from '#/lib/registry-types'

const SCOPE_CI_CHECK = 'ci:check'

interface ApiKeyCreateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (key: CreatedApiKey) => void
}

export function ApiKeyCreateDialog({ open, onOpenChange, onCreated }: ApiKeyCreateDialogProps) {
  const [name, setName] = useState('')
  const [ciCheck, setCiCheck] = useState(true)
  const [expiry, setExpiry] = useState('')
  const create = useCreateApiKey()

  function reset() {
    setName('')
    setCiCheck(true)
    setExpiry('')
    create.reset()
  }

  function handleOpenChange(next: boolean) {
    if (create.isPending) return
    if (!next) reset()
    onOpenChange(next)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    create.mutate(
      {
        name: name.trim(),
        scopes: ciCheck ? [SCOPE_CI_CHECK] : [],
        expiresAt: expiry ? endOfLocalDayIso(expiry) : null,
      },
      {
        onSuccess: (created) => {
          reset()
          onOpenChange(false)
          onCreated(created)
        },
      },
    )
  }

  const canSubmit = name.trim().length > 0 && ciCheck && !create.isPending

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-100">
        <DialogHeader>
          <DialogTitle>Create API key</DialogTitle>
          <DialogDescription>The full key is shown one time after you create it.</DialogDescription>
        </DialogHeader>

        <form className="space-y-5" onSubmit={handleSubmit}>
          {create.error && (
            <Alert variant="destructive">
              <AlertDescription>
                {create.error.message}
                {create.error instanceof ApiError && ` (trace: ${create.error.traceId})`}
              </AlertDescription>
            </Alert>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="api-key-name">
              Name<span className="text-destructive">*</span>
            </Label>
            <Input
              id="api-key-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={100}
              placeholder="github-ci"
              required
              autoFocus
            />
          </div>

          <div className="flex items-center gap-2">
            <Checkbox
              id="api-key-scope-ci-check"
              checked={ciCheck}
              onCheckedChange={(checked) => setCiCheck(checked === true)}
            />
            <Label htmlFor="api-key-scope-ci-check">ci:check</Label>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="api-key-expiry">Expires (optional)</Label>
            <Input
              id="api-key-expiry"
              type="date"
              min={toLocalDateString(new Date())}
              value={expiry}
              onChange={(e) => setExpiry(e.target.value)}
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={create.isPending}>
              Cancel
            </Button>
            <Button type="submit" disabled={!canSubmit}>
              {create.isPending ? 'Creating…' : 'Create key'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
