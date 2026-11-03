import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { apiFetch } from '#/lib/api'
import { useAuthStore } from '#/stores/useAuthStore'

// ── helpers ──────────────────────────────────────────────────────────────────

function jsonResponse(status: number, body: unknown = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'X-Trace-Id': 'abc123' },
  })
}

// ── redirectToLogin (triggered via apiFetch's 401-after-failed-refresh path) ──

describe('redirectToLogin (via apiFetch 401 handling)', () => {
  let cookieSetSpy: ReturnType<typeof vi.spyOn>
  let hrefAssignments: string[]

  beforeEach(() => {
    useAuthStore.getState().hydrateWith({
      id: 'user-1',
      email: 'test@example.com',
      name: 'Test User',
      authProvider: 'local',
      tenantId: 'ten-1',
      roles: ['MEMBER'],
    })

    cookieSetSpy = vi.spyOn(document, 'cookie', 'set')
    hrefAssignments = []
    vi.stubGlobal('window', {
      ...window,
      location: {
        ...window.location,
        pathname: '/catalog',
        search: '',
        set href(value: string) {
          hrefAssignments.push(value)
        },
        get href() {
          return hrefAssignments.at(-1) ?? ''
        },
      },
    })

    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString()
        if (url.includes('/api/auth/refresh')) {
          // refresh fails -> redirectToLogin fires
          return jsonResponse(401, { error: { code: 'UNAUTHORIZED', message: 'expired' } })
        }
        return jsonResponse(401, { error: { code: 'UNAUTHORIZED', message: 'expired' } })
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    cookieSetSpy.mockRestore()
  })

  it('clears both the auth store and the user_session cookie, then redirects to /login', async () => {
    expect(useAuthStore.getState().isAuthenticated).toBe(true)

    await expect(apiFetch('/foo')).rejects.toThrow('Session expired.')

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().user).toBeNull()

    expect(cookieSetSpy).toHaveBeenCalledWith(
      expect.stringMatching(/^user_session=;.*Max-Age=0/),
    )

    expect(hrefAssignments.at(-1)).toContain('/login?redirect=')
  })
})

// ── guest session: 401 re-mints instead of refreshing ────────────────────────

describe('apiFetch 401 in a guest session', () => {
  const guestToken = (sub: string) =>
    `h.${btoa(JSON.stringify({ sub, tid: 'demo-tenant', name: 'Demo guest', auth_provider: 'guest', roles: ['GUEST'], exp: 4102444800 }))}.s`

  let calls: string[]

  beforeEach(() => {
    useAuthStore.getState().hydrateWith({
      id: 'guest-1',
      email: '',
      name: 'Demo guest',
      authProvider: 'guest',
      tenantId: 'demo-tenant',
      roles: ['GUEST'],
    })
    calls = []
    let dataCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString()
        calls.push(`${init?.method ?? 'GET'} ${url}`)
        if (url.endsWith('/api/auth/guest')) {
          return jsonResponse(200, { data: { accessToken: guestToken('guest-2'), expiresIn: 3600 }, traceId: 't' })
        }
        dataCalls += 1
        return dataCalls === 1
          ? jsonResponse(401, { error: { code: 'UNAUTHORIZED', message: 'expired' } })
          : jsonResponse(200, { data: { ok: true }, traceId: 't' })
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('re-mints a guest token, retries, and never calls refresh', async () => {
    await expect(apiFetch<{ ok: boolean }>('/v1/registry/services')).resolves.toEqual({ ok: true })

    expect(calls).toEqual([
      'GET /api/v1/registry/services',
      'POST /api/auth/guest',
      'GET /api/v1/registry/services',
    ])
    expect(useAuthStore.getState().user?.id).toBe('guest-2')
    expect(useAuthStore.getState().user?.roles).toEqual(['GUEST'])
  })
})
