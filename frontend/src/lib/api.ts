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