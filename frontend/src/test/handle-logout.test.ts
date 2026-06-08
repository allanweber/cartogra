import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMutateMock = vi.fn()
const clearSessionCookieMock = vi.fn()
const removeQueriesMock = vi.fn()
const clearAuthMock = vi.fn()
const navigateMock = vi.fn()

vi.mock('#/lib/api', () => ({ apiMutate: apiMutateMock }))
vi.mock('#/lib/session', () => ({ clearSessionCookie: clearSessionCookieMock }))

async function handleLogout() {
  try {
    await apiMutateMock('/auth/logout', {})
  } catch {
    // proceed even if the server call fails
  }
  removeQueriesMock({ queryKey: ['session'] })
  clearSessionCookieMock()
  clearAuthMock()
  navigateMock({ to: '/login' })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('handleLogout — happy path', () => {
  it('calls all four side effects when apiMutate resolves', async () => {
    apiMutateMock.mockResolvedValue(undefined)

    await handleLogout()

    expect(removeQueriesMock).toHaveBeenCalledWith({ queryKey: ['session'] })
    expect(clearSessionCookieMock).toHaveBeenCalledOnce()
    expect(clearAuthMock).toHaveBeenCalledOnce()
    expect(navigateMock).toHaveBeenCalledWith({ to: '/login' })
  })

  it('calls side effects in order: removeQueries → clearSessionCookie → clearAuth → navigate', async () => {
    const order: string[] = []
    apiMutateMock.mockResolvedValue(undefined)
    removeQueriesMock.mockImplementation(() => order.push('removeQueries'))
    clearSessionCookieMock.mockImplementation(() => order.push('clearSessionCookie'))
    clearAuthMock.mockImplementation(() => order.push('clearAuth'))
    navigateMock.mockImplementation(() => order.push('navigate'))

    await handleLogout()

    expect(order).toEqual(['removeQueries', 'clearSessionCookie', 'clearAuth', 'navigate'])
  })
})

describe('handleLogout — error path', () => {
  it('still clears session and navigates when apiMutate throws', async () => {
    apiMutateMock.mockRejectedValue(new Error('network error'))

    await handleLogout()

    expect(removeQueriesMock).toHaveBeenCalledWith({ queryKey: ['session'] })
    expect(clearSessionCookieMock).toHaveBeenCalledOnce()
    expect(clearAuthMock).toHaveBeenCalledOnce()
    expect(navigateMock).toHaveBeenCalledWith({ to: '/login' })
  })
})
