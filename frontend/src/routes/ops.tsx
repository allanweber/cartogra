import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/ops')({
  component: OperationsPage,
})

function OperationsPage() {
  return (
    <AppLayout
      title="Operations"
      description="The runtime surface for connector health, lag visibility, and platform event triage."
    >
      <PhasePlaceholder
        deliveryPhase="Phase 5"
        summary="Operations will become the maintainer’s answer to what is broken, what is lagging, and which recovery path is safe right now."
        capabilities={[
          'Inspect connector health, background worker status, and recent platform events.',
          'Spot queue lag and replay paths before an incident spreads further.',
          'Jump from operational symptoms into the supporting observability tools.',
        ]}
      />
    </AppLayout>
  )
}