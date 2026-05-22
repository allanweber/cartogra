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

  const response = await fetch(`${baseUrl}/api${path}`, {
    credentials: 'include',
    ...init,
    headers,
  })

  const traceId = response.headers.get('X-Trace-Id') ?? 'unknown'
  const body = await response.json()

  if (!response.ok) {
    throw new ApiError(
      body.error?.code ?? 'UNKNOWN',
      body.error?.message ?? 'Request failed',
      traceId,
    )
  }

  return body.data as T
}