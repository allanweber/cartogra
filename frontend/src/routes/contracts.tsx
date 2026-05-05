import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/contracts')({
  component: ContractsPage,
})

function ContractsPage() {
  return (
    <AppLayout
      title="Contracts"
      description="A compatibility workspace for API drift, breaking changes, and CI enforcement."
    >
      <PhasePlaceholder
        deliveryPhase="Phase 3"
        summary="Contracts will centralize schema change review so producer and consumer teams can assess breaking risk before deployment."
        capabilities={[
          'Compare spec versions with structured change output, not raw diff noise.',
          'See producer-consumer compatibility status in a matrix view.',
          'Follow CI checks from failed validation back to the affected contract.',
        ]}
      />
    </AppLayout>
  )
}