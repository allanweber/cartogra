import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/intelligence')({
  component: IntelligencePage,
})

function IntelligencePage() {
  return (
    <AppLayout
      title="Intelligence"
      description="Evidence-first analysis for natural-language questions, anti-patterns, and service health narratives."
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