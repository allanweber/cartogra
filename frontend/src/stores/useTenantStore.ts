import { create } from 'zustand'

interface TenantState {
  tenantId: string | null
  setTenantId: (tenantId: string | null) => void
  clearTenant: () => void
}

export const useTenantStore = create<TenantState>((set) => ({
  tenantId: null,
  setTenantId: (tenantId) => set({ tenantId }),
  clearTenant: () => set({ tenantId: null }),
}))