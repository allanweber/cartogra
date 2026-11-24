import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import { apiFetch, apiMutate } from '#/lib/api'
import type { ApiKey, CreatedApiKey, PageResult } from '#/lib/registry-types'

const API_KEYS_KEY = ['api-keys'] as const

export function useApiKeys() {
  return useQuery({
    queryKey: API_KEYS_KEY,
    queryFn: () => apiFetch<PageResult<ApiKey>>('/v1/registry/api-keys?limit=200'),
  })
}

export interface CreateApiKeyInput {
  name: string
  scopes: string[]
  expiresAt: string | null
}

export function useCreateApiKey() {
  const queryClient = useQueryClient()
  return useMutation({
    gcTime: 0,
    mutationFn: (input: CreateApiKeyInput) => apiMutate<CreatedApiKey>('/v1/registry/api-keys', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: API_KEYS_KEY })
    },
  })
}

export function useRevokeApiKey() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiMutate<void>(`/v1/registry/api-keys/${id}`, undefined, 'DELETE'),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: API_KEYS_KEY })
    },
  })
}
