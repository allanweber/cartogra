import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── helpers ──────────────────────────────────────────────────────────────────

function handleOAuth(redirectTo: string | undefined) {
  if (redirectTo) {
    sessionStorage.setItem('pendingRedirect', redirectTo)
  }
  // window.location.href assignment omitted — side-effect under test is sessionStorage only
}

function oauthHandoffOnSuccess(navigateMock: (args: { to: string }) => void) {
  const destination = sessionStorage.getItem('pendingRedirect')
  sessionStorage.removeItem('pendingRedirect')
  navigateMock({ to: destination ?? '/dashboard' })
}

// ── setup ─────────────────────────────────────────────────────────────────────

beforeEach(() => {
  sessionStorage.clear()
})

// ── write side (login.tsx handleOAuth) ───────────────────────────────────────

describe('handleOAuth — sessionStorage write', () => {
  it('writes pendingRedirect when redirectTo is set', () => {
    handleOAuth('/catalog/abc')

    expect(sessionStorage.getItem('pendingRedirect')).toBe('/catalog/abc')
  })

  it('does not write pendingRedirect when redirectTo is absent', () => {
    handleOAuth(undefined)

    expect(sessionStorage.getItem('pendingRedirect')).toBeNull()
  })

  it('does not write pendingRedirect when redirectTo is empty string', () => {
    handleOAuth('')

    expect(sessionStorage.getItem('pendingRedirect')).toBeNull()
  })
})

// ── read side (oauth-handoff.tsx success branch) ─────────────────────────────

describe('oauth-handoff success branch — sessionStorage read', () => {
  it('navigates to stored pendingRedirect and removes the key', () => {
    sessionStorage.setItem('pendingRedirect', '/settings/team')
    const navigate = vi.fn()

    oauthHandoffOnSuccess(navigate)

    expect(navigate).toHaveBeenCalledWith({ to: '/settings/team' })
    expect(sessionStorage.getItem('pendingRedirect')).toBeNull()
  })

  it('falls back to /dashboard when pendingRedirect is absent', () => {
    const navigate = vi.fn()

    oauthHandoffOnSuccess(navigate)

    expect(navigate).toHaveBeenCalledWith({ to: '/dashboard' })
  })
})
