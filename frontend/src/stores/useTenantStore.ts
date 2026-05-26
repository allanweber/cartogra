import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export interface Tenant {
  id: string
  name: string
  plan: string
}

export const TENANTS: Tenant[] = [
  { id: 'ten-1', name: 'Acme Fintech', plan: 'Alpha' },
  { id: 'ten-2', name: 'Beta Corp', plan: 'Alpha' },
  { id: 'ten-3', name: 'Gamma Ltd', plan: 'Alpha' },
]

interface TenantState {
  currentTenantId: string
  setTenant: (id: string) => void
}

export const useTenantStore = create<TenantState>()(
  persist(
    (set) => ({
      currentTenantId: 'ten-1',
      setTenant: (id) => set({ currentTenantId: id }),
    }),
    { name: 'cartogra-tenant' },
  ),
)

export function useCurrentTenant(): Tenant {
  const id = useTenantStore((s) => s.currentTenantId)
  return TENANTS.find((t) => t.id === id) ?? TENANTS[0]
}