import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/catalog')({
  component: CatalogPage,
})

function CatalogPage() {
  return (
    <AppLayout
      title="Catalog"
      description="A fast service registry for ownership, health, and change-risk decisions."
    >
      <PhasePlaceholder
        deliveryPhase="Phase 1"
        summary="Catalog will become the default landing surface for service discovery, owner lookup, health triage, and safe-change investigation."
        capabilities={[
          'Filter services by team, health, tech stack, and SCM provider.',
          'Highlight orphans and ownership gaps without opening another tool.',
          'Jump from list view into detailed dependency and contract context.',
        ]}
      />
    </AppLayout>
  )
}