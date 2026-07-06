import { create } from 'zustand'

export interface AuthUser {
  id: string
  email: string
  name: string | null
  authProvider: string
  tenantId: string
  roles: string[]
}

interface AuthState {
  user: AuthUser | null
  isAuthenticated: boolean
  isHydrated: boolean
  tokenExpiresAt: number | null // unix seconds
  setUser: (user: AuthUser | null) => void
  clearAuth: () => void
  markHydrated: () => void
  hydrateWith: (user: AuthUser, tokenExpiresAt?: number) => void
  setTokenExpiresAt: (v: number) => void
}

export const useAuthStore = create<AuthState>()((set) => ({
  user: null,
  isAuthenticated: false,
  isHydrated: false,
  tokenExpiresAt: null,
  setUser: (user) => set({ user, isAuthenticated: user !== null }),
  clearAuth: () => set({ user: null, isAuthenticated: false, tokenExpiresAt: null }),
  markHydrated: () => set({ isHydrated: true }),
  hydrateWith: (user, tokenExpiresAt) =>
    set({ user, isAuthenticated: true, isHydrated: true, ...(tokenExpiresAt != null ? { tokenExpiresAt } : {}) }),
  setTokenExpiresAt: (v) => set({ tokenExpiresAt: v }),
}))
