import { Check, Copy } from 'lucide-react'
import { useState } from 'react'

import { Button } from '#/components/ui/button'
import { workflowSnippet } from '#/lib/workflow-snippet'

/** A GitHub workflow for a `ci:check` key. It uses the secret name, never the key. */
export function ApiKeyWorkflowSnippet() {
  const [copied, setCopied] = useState(false)
  const snippet = workflowSnippet(window.location.origin)

  async function copy() {
    try {
      await navigator.clipboard.writeText(snippet)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm font-medium">GitHub workflow</p>
        <Button type="button" variant="outline" size="sm" onClick={copy} className="gap-1.5">
          {copied ? <Check className="size-3.5" aria-hidden="true" /> : <Copy className="size-3.5" aria-hidden="true" />}
          {copied ? 'Copied' : 'Copy workflow'}
        </Button>
      </div>
      <pre
        data-testid="api-key-workflow"
        className="max-h-64 overflow-auto rounded-md bg-muted px-3 py-2 font-mono text-xs"
      >
        <code>{snippet}</code>
      </pre>
    </div>
  )
}
