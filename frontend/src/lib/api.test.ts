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
