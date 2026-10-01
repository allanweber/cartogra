import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { PageResult, RegistryService } from '#/lib/registry-types'
import type { Risk } from '#/lib/topology-types'

// Each risk source is capped at 200 server-side; one bounded fetch lets callers filter and
// count across the whole list instead of page-flipping.
export const RISKS_LIMIT = 200

export function useRisks() {
  return useQuery({
    queryKey: ['risks'],
    queryFn: () => apiFetch<PageResult<Risk>>(`/v1/topology/risks?limit=${RISKS_LIMIT}&offset=0`),
    // Risks are derived live from topology state; the layout-mounted bell would otherwise seed a
    // fresh-looking cache that hides new cycles/SPOFs from the Risks page and dashboard.
    staleTime: 0,
  })
}

export function useServiceNames() {
  const { data } = useQuery({
    queryKey: ['services'],
    queryFn: () => apiFetch<PageResult<RegistryService>>('/v1/registry/services?limit=1000'),
  })
  return new Map((data?.items ?? []).map((s) => [s.id, s.name]))
}

export function formatAffected(ids: string[], names: Map<string, string>): string {
  return ids.map((id) => names.get(id) ?? id).join(', ')
}
