import { keepPreviousData, useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'

import type { CompatibilityStatus, Contract, ContractsPage, ContractVersionsPage, VersionDiff } from '#/lib/contract-types'

export const CONTRACTS_PAGE_SIZE = 20
export const VERSIONS_LIMIT = 100

export function useContracts(q: string, page: number, status?: CompatibilityStatus) {
  return useQuery({
    queryKey: ['contracts', q, page, status ?? 'all'],
    placeholderData: keepPreviousData,
    queryFn: () => {
      const params = new URLSearchParams({ limit: String(CONTRACTS_PAGE_SIZE), offset: String(page * CONTRACTS_PAGE_SIZE) })
      if (q) params.set('q', q)
      if (status) params.set('status', status)
      return apiFetch<ContractsPage>(`/v1/contracts?${params}`)
    },
  })
}

export function useContract(contractId: string) {
  return useQuery({
    queryKey: ['contracts', contractId],
    queryFn: () => apiFetch<Contract>(`/v1/contracts/${contractId}`),
  })
}

export function useContractVersions(contractId: string) {
  return useQuery({
    queryKey: ['contracts', contractId, 'versions'],
    queryFn: () => apiFetch<ContractVersionsPage>(`/v1/contracts/${contractId}/versions?limit=${VERSIONS_LIMIT}&offset=0`),
  })
}

export function useVersionDiff(contractId: string, versionNumber: number | undefined) {
  return useQuery({
    queryKey: ['contracts', contractId, 'diff', versionNumber],
    enabled: versionNumber !== undefined,
    queryFn: () => apiFetch<VersionDiff>(`/v1/contracts/${contractId}/versions/${versionNumber}/diff`),
    staleTime: Infinity,
  })
}
