import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { UnresolvedSpanServicesPage } from '#/lib/topology-types'

export const UNRESOLVED_SPAN_SERVICES_KEY = ['unresolved-span-services'] as const

export function useUnresolvedSpanServices(limit: number) {
  return useQuery({
    queryKey: [...UNRESOLVED_SPAN_SERVICES_KEY, limit],
    queryFn: () =>
      apiFetch<UnresolvedSpanServicesPage>(`/v1/topology/unresolved-span-services?limit=${limit}&offset=0`),
    placeholderData: (previous) => previous,
  })
}
