import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { DriftsPage } from '#/lib/topology-types'

export const DRIFTS_LIMIT = 200

export function useDrifts() {
  return useQuery({
    queryKey: ['drifts'],
    queryFn: () => apiFetch<DriftsPage>(`/v1/topology/drifts?limit=${DRIFTS_LIMIT}&offset=0`),
    staleTime: 0,
  })
}
