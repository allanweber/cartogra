import { createMiddleware } from '@tanstack/react-start'
import { getCookie } from '@tanstack/react-start/server'

import type { AuthUser } from '#/stores/useAuthStore'

const GATEWAY_URL =
  typeof process !== 'undefined'
    ? (process.env['GATEWAY_INTERNAL_URL'] ?? 'http://localhost:8080')
    : ''

const SESSION_COOKIE = 'user_session'

export const sessionMiddleware = createMiddleware({ type: 'function' }).server(async ({ next }) => {
  let user: AuthUser | null = null

  const jwt = getCookie('jwt')
  if (jwt) {
    try {
      const res = await fetch(`${GATEWAY_URL}/api/auth/userinfo`, {
        headers: { cookie: `jwt=${jwt}` },
      })
      if (res.ok) {
        const body = await res.json()
        user = (body?.data ?? null) as AuthUser | null
      }
    } catch {
      // fall through to session cookie
    }
  }

  if (!user) {
    const encoded = getCookie(SESSION_COOKIE)
    if (encoded) {
      try {
        user = JSON.parse(atob(encoded)) as AuthUser
      } catch {
        // ignore malformed cookie
      }
    }
  }

  return next({ context: { user } })
})
