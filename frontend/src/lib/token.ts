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
  // atob() returns one character per byte, but the payload is UTF-8, so
  // decode the bytes instead of reading them as Latin-1.
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
  return JSON.parse(new TextDecoder().decode(bytes)) as TokenPayload
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
