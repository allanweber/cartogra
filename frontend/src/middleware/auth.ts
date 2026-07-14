import { createMiddleware } from '@tanstack/react-start'
import { getCookie, setCookie } from '@tanstack/react-start/server'

import type { AuthUser } from '#/stores/useAuthStore'

const GATEWAY_URL =
  typeof process !== 'undefined'
    ? (process.env['GATEWAY_INTERNAL_URL'] ?? 'http://localhost:8080')
    : ''

async function fetchUserInfo(jwt: string): Promise<AuthUser | null> {
  try {
    const res = await fetch(`${GATEWAY_URL}/api/auth/userinfo`, {
      headers: { cookie: `jwt=${jwt}` },
    })
    if (!res.ok) return null
    const body = await res.json()
    return (body?.data ?? null) as AuthUser | null
  } catch {
    return null
  }
}

async function tryServerRefresh(
  refreshToken: string,
): Promise<{ accessToken: string; refreshToken: string; expiresIn: number } | null> {
  try {
    const res = await fetch(`${GATEWAY_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { cookie: `jwt_refresh=${refreshToken}` },
    })
    if (!res.ok) return null
    const body = await res.json()
    return (body?.data ?? null) as { accessToken: string; refreshToken: string; expiresIn: number } | null
  } catch {
    return null
  }
}

function decodeJwtExp(jwt: string): number | null {
  try {
    const payload = JSON.parse(
      Buffer.from(jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf-8'),
    )
    return typeof payload.exp === 'number' ? payload.exp : null
  } catch {
    return null
  }
}

export const sessionMiddleware = createMiddleware({ type: 'function' }).server(async ({ next }) => {
  let user: AuthUser | null = null
  let tokenExpiresAt: number | null = null

  const jwt = getCookie('jwt')
  if (jwt) {
    user = await fetchUserInfo(jwt)
    if (user) {
      tokenExpiresAt = decodeJwtExp(jwt)
    }
  }

  if (!user) {
    const refreshToken = getCookie('jwt_refresh')
    if (refreshToken) {
      const tokens = await tryServerRefresh(refreshToken)
      if (tokens) {
        setCookie('jwt', tokens.accessToken, {
          httpOnly: true,
          secure: true,
          sameSite: 'lax',
          path: '/',
          maxAge: tokens.expiresIn,
        })
        setCookie('jwt_refresh', tokens.refreshToken, {
          httpOnly: true,
          secure: true,
          sameSite: 'lax',
          path: '/',
          maxAge: 30 * 24 * 3600,
        })
        user = await fetchUserInfo(tokens.accessToken)
        if (user) {
          tokenExpiresAt = Math.floor(Date.now() / 1000) + tokens.expiresIn
        }
      }
    }
  }

  return next({ context: { user, tokenExpiresAt } })
})
