import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

export interface AuthFeatures {
  signupEnabled: boolean
  guestEnabled: boolean
}

export function useAuthFeatures() {
  return useQuery({
    queryKey: ['auth-features'],
    queryFn: () => apiFetch<AuthFeatures>('/auth/features'),
    staleTime: Infinity,
  })
}
