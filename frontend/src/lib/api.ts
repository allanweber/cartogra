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

function redirectToLogin() {
  if (typeof window === 'undefined') return
  useAuthStore.getState().clearAuth()
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}

async function parseResponse<T>(response: Response): Promise<T> {
  const traceId = response.headers.get('X-Trace-Id') ?? 'unknown'

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
    const refreshed = await tryRefresh(baseUrl)
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