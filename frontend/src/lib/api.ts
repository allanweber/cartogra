import { isGuestUser } from '#/lib/guest'
import { clearSessionCookie, writeSessionCookie } from '#/lib/session'
import { sessionFromAccessToken } from '#/lib/token'
import { useAuthStore } from '#/stores/useAuthStore'
import { useTenantStore } from '#/stores/useTenantStore'

export class ApiError extends Error {
  code: string
  traceId: string

  constructor(code: string, message: string, traceId: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export type RefreshResult = { expiresIn: number } | null

// Singleton promise — concurrent 401s all await the same refresh attempt.
let refreshPromise: Promise<RefreshResult> | null = null

export async function tryRefresh(baseUrl: string): Promise<RefreshResult> {
  if (!refreshPromise) {
    refreshPromise = fetch(`${baseUrl}/api/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
    })
      .then(async (r) => {
        if (!r.ok) {
          console.warn('[auth] token refresh failed with status', r.status)
          return null
        }
        const body = (await r.json()) as { data?: { expiresIn: number } }
        return body.data ? { expiresIn: body.data.expiresIn } : null
      })
      .catch((err) => {
        console.warn('[auth] token refresh error', err)
        return null
      })
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

let guestMintPromise: Promise<RefreshResult> | null = null

/** Starts (or replaces) a guest session: sets the jwt cookie server-side and hydrates the auth store. */
export function startGuestSession(): Promise<RefreshResult> {
  if (!guestMintPromise) {
    guestMintPromise = apiFetch<{ accessToken: string; expiresIn: number }>('/auth/guest', { method: 'POST' })
      .then((tokens) => {
        const { user, exp } = sessionFromAccessToken(tokens.accessToken)
        writeSessionCookie(user, tokens.expiresIn)
        useAuthStore.getState().hydrateWith(user, exp)
        return { expiresIn: tokens.expiresIn }
      })
      .finally(() => {
        guestMintPromise = null
      })
  }
  return guestMintPromise
}

/** Silent re-mint for an expired guest session; null on failure. */
export async function tryGuestRemint(): Promise<RefreshResult> {
  try {
    return await startGuestSession()
  } catch (err) {
    console.warn('[auth] guest re-mint failed', err)
    return null
  }
}

/** Renews an expiring session: guests get a fresh guest token, everyone else uses the refresh cookie. */
export function renewSession(baseUrl: string): Promise<RefreshResult> {
  return isGuestUser(useAuthStore.getState().user) ? tryGuestRemint() : tryRefresh(baseUrl)
}

function redirectToLogin() {
  if (typeof window === 'undefined') return
  useAuthStore.getState().clearAuth()
  clearSessionCookie()
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

async function parseResponse<T>(response: Response): Promise<T> {
  const traceId = response.headers.get('X-Trace-Id') ?? 'unknown'

  // 204 has no body by spec; 202 (accepted, no representation yet) is used the same way
  // by a couple of async-trigger endpoints (e.g. ScmConnectionController#triggerSync).
  if (response.status === 204 || (response.status === 202 && response.headers.get('content-length') === '0')) {
    if (!response.ok) {
      throw new ApiError('UNKNOWN', 'Request failed.', traceId)
    }
    return undefined as T
  }

  let body: Record<string, unknown>
  try {
    body = await response.json()
  } catch {
    throw new ApiError('PARSE_ERROR', `Unexpected server response (HTTP ${response.status}).`, traceId)
  }

  if (!response.ok) {
    const err = body.error as { code?: string; message?: string } | undefined
    throw new ApiError(err?.code ?? 'UNKNOWN', err?.message ?? 'Request failed.', traceId)
  }

  return body.data as T
}

export async function apiFetch<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const baseUrl = import.meta.env.VITE_API_BASE_URL ?? ''
  const tenantId = useTenantStore.getState().currentTenantId

  const headers = new Headers(init?.headers)
  if (tenantId) {
    headers.set('X-Tenant-Id', tenantId)
  }

  let response: Response
  try {
    response = await fetch(`${baseUrl}/api${path}`, {
      credentials: 'include',
      ...init,
      headers,
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', 'Unable to reach the server. Check your connection.', 'unknown')
  }

  // Auth endpoints return 401 for bad credentials — don't treat them as expired sessions.
  const isAuthEndpoint = path.startsWith('/auth/')

  if (response.status === 401 && !isAuthEndpoint) {
    const refreshed = await renewSession(baseUrl)
    if (!refreshed) {
      redirectToLogin()
      throw new ApiError('UNAUTHORIZED', 'Session expired.', response.headers.get('X-Trace-Id') ?? 'unknown')
    }
    if (refreshed.expiresIn) {
      useAuthStore.getState().setTokenExpiresAt(Math.floor(Date.now() / 1000) + refreshed.expiresIn)
    }
    // Retry once with fresh cookies — browser sends the new jwt cookie automatically.
    try {
      response = await fetch(`${baseUrl}/api${path}`, {
        credentials: 'include',
        ...init,
        headers,
      })
    } catch {
      throw new ApiError('NETWORK_ERROR', 'Unable to reach the server. Check your connection.', 'unknown')
    }
    // If the retry is also 401 (e.g. insufficient permissions), redirect — don't loop.
    if (response.status === 401) {
      redirectToLogin()
      throw new ApiError('UNAUTHORIZED', 'Session expired.', response.headers.get('X-Trace-Id') ?? 'unknown')
    }
  }

  return parseResponse<T>(response)
}

export async function apiMutate<T>(
  path: string,
  body: unknown,
  method: 'POST' | 'PUT' | 'PATCH' | 'DELETE' = 'POST',
): Promise<T> {
  return apiFetch<T>(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}