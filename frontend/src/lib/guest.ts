import type { AuthUser } from '#/stores/useAuthStore'

export const GUEST_ROLE = 'GUEST'

export function isGuestUser(user: AuthUser | null | undefined): boolean {
  return user?.roles.includes(GUEST_ROLE) ?? false
}
