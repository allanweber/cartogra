import { createFileRoute } from '@tanstack/react-router'

import { AppLayout } from '#/components/AppLayout'
import { PhasePlaceholder } from '#/components/PhasePlaceholder'

export const Route = createFileRoute('/_authenticated/graph')({
  component: GraphPage,
})

function GraphPage() {
  return (
    <AppLayout
      title="Graph"
      description="A dependency map for blast radius, single points of failure, and drift visibility."
    >
      <PhasePlaceholder
        deliveryPhase="Phase 2"
        summary="Graph will help operators see declared and observed dependencies in one place, then move from structure to impact without context switching."
        capabilities={[
          'Inspect upstream and downstream relationships from a selected service.',
          'Overlay declared versus observed edges to spot dependency drift.',
          'Surface cycle and SPOF findings where architectural risk is highest.',
        ]}
      />
    </AppLayout>
  )
}