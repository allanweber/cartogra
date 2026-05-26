import { create } from 'zustand'

export interface AuthUser {
  id: string
  email: string
  tenantId: string
  roles: string[]
}

interface AuthState {
  user: AuthUser | null
  isAuthenticated: boolean
  isHydrated: boolean
  setUser: (user: AuthUser | null) => void
  clearAuth: () => void
  markHydrated: () => void
  hydrateWith: (user: AuthUser) => void
}

export const useAuthStore = create<AuthState>()((set) => ({
  user: null,
  isAuthenticated: false,
  isHydrated: false,
  setUser: (user) => set({ user, isAuthenticated: user !== null }),
  clearAuth: () => set({ user: null, isAuthenticated: false }),
  markHydrated: () => set({ isHydrated: true }),
  hydrateWith: (user: AuthUser) => set({ user, isAuthenticated: true, isHydrated: true }),
}))
