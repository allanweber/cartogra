import { describe, expect, it } from 'vitest'

import { sessionFromAccessToken } from '#/lib/token'

function tokenWith(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(payload))))
  return `${header}.${body}.signature`
}

describe('sessionFromAccessToken', () => {
  it('maps the ASCII claims onto the auth user', () => {
    const session = sessionFromAccessToken(
      tokenWith({
        sub: 'user-1',
        tid: 'tenant-1',
        email: 'ada@example.com',
        name: 'Ada Lovelace',
        auth_provider: 'local',
        roles: ['ADMIN'],
        exp: 1_900_000_000,
      }),
    )

    expect(session.user).toEqual({
      id: 'user-1',
      email: 'ada@example.com',
      name: 'Ada Lovelace',
      authProvider: 'local',
      tenantId: 'tenant-1',
      roles: ['ADMIN'],
    })
    expect(session.exp).toBe(1_900_000_000)
  })

  it('keeps non-ASCII names intact', () => {
    const session = sessionFromAccessToken(
      tokenWith({
        sub: 'user-2',
        tid: 'tenant-1',
        email: 'jose@example.com',
        name: 'José Müller',
        auth_provider: 'local',
        roles: [],
        exp: 1_900_000_000,
      }),
    )

    expect(session.user.name).toBe('José Müller')
  })

  it('falls back when email and name claims are missing', () => {
    const session = sessionFromAccessToken(
      tokenWith({
        sub: 'user-3',
        tid: 'tenant-1',
        auth_provider: 'local',
        roles: [],
        exp: 1_900_000_000,
      }),
    )

    expect(session.user.email).toBe('')
    expect(session.user.name).toBeNull()
  })
})
