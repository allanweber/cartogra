import type { AuthUser } from '#/stores/useAuthStore'

interface TokenPayload {
  sub: string
  tid: string
  email?: string | null
  name?: string | null
  auth_provider: string
  roles: string[]
  exp: number
}

function decodeJwtPayload(token: string): TokenPayload {
  const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  return JSON.parse(atob(base64)) as TokenPayload
}

export function sessionFromAccessToken(token: string): { user: AuthUser; exp: number } {
  const claims = decodeJwtPayload(token)
  return {
    user: {
      id: claims.sub,
      email: claims.email ?? '',
      name: claims.name ?? null,
      authProvider: claims.auth_provider,
      tenantId: claims.tid,
      roles: claims.roles,
    },
    exp: claims.exp,
  }
}
