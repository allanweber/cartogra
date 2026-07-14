import { beforeEach, describe, expect, it, vi } from 'vitest'

const getCookieMock = vi.fn()
const setCookieMock = vi.fn()

vi.mock('@tanstack/react-start/server', () => ({
  getCookie: getCookieMock,
  setCookie: setCookieMock,
}))

const fetchMock = vi.fn()
vi.stubGlobal('fetch', fetchMock)

async function runMiddleware() {
  const { sessionMiddleware } = await import('./auth')
  const server = (sessionMiddleware as unknown as { options: { server: (opts: unknown) => unknown } })
    .options.server
  const next = vi.fn(async (ctx?: { context?: unknown }) => ctx)
  return server({ next }) as Promise<unknown>
}

function jsonResponse(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, json: async () => body }
}

beforeEach(() => {
  vi.resetModules()
  getCookieMock.mockReset()
  setCookieMock.mockReset()
  fetchMock.mockReset()
})

describe('sessionMiddleware', () => {
  it('does NOT trust a forged user_session cookie when jwt and jwt_refresh are absent', async () => {
    getCookieMock.mockImplementation((name: string) => {
      if (name === 'user_session') {
        return btoa(JSON.stringify({ id: 'x', tenantId: 'attacker-chosen', roles: ['ADMIN'] }))
      }
      return undefined
    })

    const result = (await runMiddleware()) as { context: { user: unknown; tokenExpiresAt: unknown } }

    expect(result.context.user).toBeNull()
    expect(result.context.tokenExpiresAt).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('populates context.user and tokenExpiresAt from a valid jwt cookie', async () => {
    const payload = { exp: 9999999999 }
    const encodedPayload = Buffer.from(JSON.stringify(payload)).toString('base64url')
    const jwt = `header.${encodedPayload}.sig`
    const user = { id: 'u1', email: 'a@b.com', name: null, authProvider: 'local', tenantId: 't1', roles: ['user'] }

    getCookieMock.mockImplementation((name: string) => (name === 'jwt' ? jwt : undefined))
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/api/auth/userinfo')) {
        return Promise.resolve(jsonResponse(200, { data: user }))
      }
      return Promise.resolve(jsonResponse(404, {}))
    })

    const result = (await runMiddleware()) as { context: { user: unknown; tokenExpiresAt: unknown } }

    expect(result.context.user).toEqual(user)
    expect(result.context.tokenExpiresAt).toBe(9999999999)
  })

  it('refreshes tokens and sets new cookies when only jwt_refresh is present and refresh succeeds', async () => {
    const user = { id: 'u1', email: 'a@b.com', name: null, authProvider: 'local', tenantId: 't1', roles: ['user'] }
    const tokens = { accessToken: 'new-access', refreshToken: 'new-refresh', expiresIn: 3600 }

    getCookieMock.mockImplementation((name: string) => (name === 'jwt_refresh' ? 'old-refresh' : undefined))
    fetchMock.mockImplementation((url: string) => {
      if (url.includes('/api/auth/refresh')) {
        return Promise.resolve(jsonResponse(200, { data: tokens }))
      }
      if (url.includes('/api/auth/userinfo')) {
        return Promise.resolve(jsonResponse(200, { data: user }))
      }
      return Promise.resolve(jsonResponse(404, {}))
    })

    const result = (await runMiddleware()) as { context: { user: unknown; tokenExpiresAt: unknown } }

    expect(setCookieMock).toHaveBeenCalledWith(
      'jwt',
      'new-access',
      expect.objectContaining({ httpOnly: true, secure: true, sameSite: 'lax' }),
    )
    expect(setCookieMock).toHaveBeenCalledWith(
      'jwt_refresh',
      'new-refresh',
      expect.objectContaining({ httpOnly: true, secure: true, sameSite: 'lax' }),
    )
    expect(result.context.user).toEqual(user)
    expect(typeof (result.context as { tokenExpiresAt: number }).tokenExpiresAt).toBe('number')
  })

  it('resolves context.user to null when neither jwt nor jwt_refresh cookies are present', async () => {
    getCookieMock.mockImplementation(() => undefined)

    const result = (await runMiddleware()) as { context: { user: unknown; tokenExpiresAt: unknown } }

    expect(result.context.user).toBeNull()
    expect(result.context.tokenExpiresAt).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
