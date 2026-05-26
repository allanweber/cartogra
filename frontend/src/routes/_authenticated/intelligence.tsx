import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/_authenticated/intelligence')({
  component: IntelligencePage,
})

function IntelligencePage() {
  return (
    <AppLayout
      title="Intelligence"
      description="NL queries, anti-pattern findings, and health narratives — grounded in evidence."
    >
      <PhasePlaceholder
        deliveryPhase="Phase 4"
        summary="Intelligence will turn system data into explainable findings, but it will stay grounded in deterministic evidence instead of opaque model guesses."
        capabilities={[
          'Ask guarded natural-language questions against tenant-scoped system data.',
          'Review anti-pattern findings with supporting evidence and recommended actions.',
          'Track health score shifts and weekly digest recommendations over time.',
        ]}
      />
    </AppLayout>
  )
}