import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { PageResult, RegistryService } from '#/lib/registry-types'
import type { RisksPage } from '#/lib/topology-types'

// Each risk source is capped at 200 server-side; one bounded fetch lets callers filter and
// count across the whole list instead of page-flipping.
export const RISKS_LIMIT = 200

export function useRisks({ staleTime = 0 }: { staleTime?: number } = {}) {
  return useQuery({
    queryKey: ['risks'],
    queryFn: () => apiFetch<RisksPage>(`/v1/topology/risks?limit=${RISKS_LIMIT}&offset=0`),
    // Risks are derived live from topology state. staleTime is per-observer, so the layout-mounted
    // bell can poll gently (see NotificationBell) while the Risks page and dashboard stay always-stale.
    staleTime,
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
