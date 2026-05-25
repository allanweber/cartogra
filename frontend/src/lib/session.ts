import { createServerFn } from '@tanstack/react-start'

import { sessionMiddleware } from '#/middleware/auth'
import type { AuthUser } from '#/stores/useAuthStore'

const SESSION_COOKIE = 'user_session'

export function writeSessionCookie(user: AuthUser, maxAgeSeconds: number) {
  document.cookie = `${SESSION_COOKIE}=${btoa(JSON.stringify(user))}; path=/; SameSite=Lax; Max-Age=${maxAgeSeconds}`
}

export function clearSessionCookie() {
  document.cookie = `${SESSION_COOKIE}=; path=/; SameSite=Lax; Max-Age=0`
}

export const fetchSession = createServerFn({ method: 'GET' })
  .middleware([sessionMiddleware])
  .handler(({ context }): AuthUser | null => context.user)
