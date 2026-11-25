import { Check, Copy } from 'lucide-react'
import { useState } from 'react'

import { ApiKeyWorkflowSnippet } from '#/components/ApiKeyWorkflowSnippet'
import { Button } from '#/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '#/components/ui/dialog'
import type { CreatedApiKey } from '#/lib/registry-types'

interface ApiKeyRevealDialogProps {
  created: CreatedApiKey | null
  onClose: () => void
}

/** Shows the full key one time. The parent clears `created` on close, so the key leaves the state. */
export function ApiKeyRevealDialog({ created, onClose }: ApiKeyRevealDialogProps) {
  const [copied, setCopied] = useState(false)

  async function copy() {
    if (!created) return
    try {
      await navigator.clipboard.writeText(created.key)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  function handleOpenChange(next: boolean) {
    if (next) return
    setCopied(false)
    onClose()
  }

  return (
    <Dialog open={created !== null} onOpenChange={handleOpenChange}>
      <DialogContent className="sm:max-w-160" onInteractOutside={(e) => e.preventDefault()}>
        <DialogHeader>
          <DialogTitle>Copy your API key</DialogTitle>
          <DialogDescription>
            This is the only time the full key is shown. Store it in a safe place now.
          </DialogDescription>
        </DialogHeader>

        <div className="flex items-center gap-2">
          <code
            data-testid="api-key-secret"
            className="min-w-0 flex-1 break-all rounded-md bg-muted px-3 py-2 font-mono text-xs"
          >
            {created?.key}
          </code>
          <Button type="button" variant="outline" size="sm" onClick={copy} className="gap-1.5">
            {copied ? <Check className="size-3.5" aria-hidden="true" /> : <Copy className="size-3.5" aria-hidden="true" />}
            {copied ? 'Copied' : 'Copy'}
          </Button>
        </div>

        {created?.scopes.includes('ci:check') && <ApiKeyWorkflowSnippet />}

        <DialogFooter>
          <Button type="button" onClick={() => handleOpenChange(false)}>
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
