import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '#/stores/useAuthStore'
import type { AuthUser } from '#/stores/useAuthStore'

const testUser: AuthUser = {
  id: 'user-1',
  email: 'test@example.com',
  tenantId: 'tenant-1',
  roles: ['user'],
}

beforeEach(() => {
  useAuthStore.setState({ user: null, isAuthenticated: false, isHydrated: false })
})

describe('clearAuth', () => {
  it('resets user to null and isAuthenticated to false', () => {
    useAuthStore.setState({ user: testUser, isAuthenticated: true, isHydrated: true })

    useAuthStore.getState().clearAuth()

    const { user, isAuthenticated } = useAuthStore.getState()
    expect(user).toBeNull()
    expect(isAuthenticated).toBe(false)
  })
})

describe('hydrateWith', () => {
  it('sets user, isAuthenticated, and isHydrated', () => {
    useAuthStore.getState().hydrateWith(testUser)

    const { user, isAuthenticated, isHydrated } = useAuthStore.getState()
    expect(user).toEqual(testUser)
    expect(isAuthenticated).toBe(true)
    expect(isHydrated).toBe(true)
  })
})
