import { useInfiniteQuery, useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { AuditEventsPage } from '#/lib/audit-types'

export const AUDIT_PAGE_SIZE = 20

export function useAuditEvents(entityTypes: string[] | null) {
  return useInfiniteQuery({
    queryKey: ['audit-events', entityTypes ?? 'all'],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: String(AUDIT_PAGE_SIZE), offset: String(pageParam) })
      if (entityTypes) params.set('entityType', entityTypes.join(','))
      return apiFetch<AuditEventsPage>(`/v1/registry/audit-events?${params}`)
    },
    initialPageParam: 0,
    getNextPageParam: (last) =>
      last.offset + last.items.length < last.total ? last.offset + last.items.length : undefined,
  })
}

export function useRecentAuditEvents(limit: number) {
  return useQuery({
    queryKey: ['audit-events', 'recent', limit],
    queryFn: () => apiFetch<AuditEventsPage>(`/v1/registry/audit-events?limit=${limit}&offset=0`),
  })
}
