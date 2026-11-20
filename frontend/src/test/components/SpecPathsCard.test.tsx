import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { SpecPathsCard, specPathError } from '#/components/SpecPathsCard'
import type { SpecPaths } from '#/components/SpecPathsCard'
import { ApiError, apiFetch, apiMutate } from '#/lib/api'
import { useAuthStore } from '#/stores/useAuthStore'

vi.mock('#/lib/api', () => ({
  apiFetch: vi.fn(),
  apiMutate: vi.fn(),
  ApiError: class extends Error {
    code: string
    traceId: string
    constructor(code: string, message: string, traceId: string) {
      super(message)
      this.code = code
      this.traceId = traceId
    }
  },
}))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

const DEFAULTS = ['openapi.yaml', 'openapi.yml', 'openapi.json', 'api/openapi.yaml', 'docs/openapi.yaml']
const PATHS: SpecPaths = { defaults: DEFAULTS, custom: ['spec/api.yaml'] }

function setRoles(roles: string[]) {
  useAuthStore.setState({
    user: { id: 'u1', email: 'a@b.c', name: null, authProvider: 'local', tenantId: 't1', roles },
    isAuthenticated: true,
  })
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <SpecPathsCard />
    </QueryClientProvider>,
  )
}

describe('SpecPathsCard', () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockResolvedValue(PATHS)
  })

  afterEach(() => {
    vi.resetAllMocks()
  })

  it('shows the defaults read-only and the custom paths', async () => {
    setRoles(['ADMIN'])
    renderCard()

    const defaults = await screen.findByRole('list', { name: 'Default spec paths' })
    expect(within(defaults).getAllByRole('listitem')).toHaveLength(5)
    expect(within(defaults).queryByRole('button')).not.toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Custom spec paths' })).getByText('spec/api.yaml')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/v1/ingestion/spec-paths')
  })

  it('lets an admin add a path, which sends the full custom list', async () => {
    setRoles(['ADMIN'])
    vi.mocked(apiMutate).mockResolvedValue({ defaults: DEFAULTS, custom: ['spec/api.yaml', 'svc/openapi.yml'] })
    renderCard()

    fireEvent.change(await screen.findByLabelText('Custom spec path'), { target: { value: ' svc/openapi.yml ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add path' }))

    await waitFor(() => expect(apiMutate).toHaveBeenCalledWith(
      '/v1/ingestion/spec-paths', { custom: ['spec/api.yaml', 'svc/openapi.yml'] }, 'PUT'))
    expect(await screen.findByText('svc/openapi.yml')).toBeInTheDocument()
  })

  it('lets an admin remove a path', async () => {
    setRoles(['ADMIN'])
    vi.mocked(apiMutate).mockResolvedValue({ defaults: DEFAULTS, custom: [] })
    renderCard()

    fireEvent.click(await screen.findByRole('button', { name: 'Remove spec/api.yaml' }))

    await waitFor(() => expect(apiMutate).toHaveBeenCalledWith('/v1/ingestion/spec-paths', { custom: [] }, 'PUT'))
    expect(await screen.findByText('No custom paths.')).toBeInTheDocument()
  })

  it('shows a client validation error and sends nothing', async () => {
    setRoles(['ADMIN'])
    renderCard()

    fireEvent.change(await screen.findByLabelText('Custom spec path'), { target: { value: 'specs/*.yaml' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add path' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Glob patterns are not supported')
    expect(screen.getByLabelText('Custom spec path')).toHaveAttribute('aria-describedby', alert.id)
    expect(apiMutate).not.toHaveBeenCalled()
  })

  it('is read-only for a non-admin', async () => {
    setRoles(['MEMBER'])
    renderCard()

    expect(await screen.findByText('spec/api.yaml')).toBeInTheDocument()
    expect(screen.queryByLabelText('Custom spec path')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
    expect(screen.getByText('Only an admin can change the custom paths.')).toBeInTheDocument()
  })

  it('shows the trace id when the load fails', async () => {
    setRoles(['ADMIN'])
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Boom', 'trace-123'))
    renderCard()

    expect(await screen.findByText(/Boom/)).toHaveTextContent('(trace: trace-123)')
  })
})

describe('specPathError', () => {
  it.each([
    ['', 'Enter a path'],
    ['/abs.yaml', 'Use a path relative to the repository root'],
    ['../x.yaml', "Do not use '..'"],
    ['a{b}.yaml', 'Glob patterns are not supported'],
    ['openapi.yaml', 'This path is already a default'],
    ['spec/api.yaml', 'This path is already in the list'],
    ['a'.repeat(257), 'Use 256 characters or fewer'],
  ])('rejects %j', (path, message) => {
    expect(specPathError(path, PATHS)).toBe(message)
  })

  it('rejects an eleventh path', () => {
    const full = { defaults: DEFAULTS, custom: Array.from({ length: 10 }, (_, i) => `p${i}.yaml`) }
    expect(specPathError('new.yaml', full)).toBe('You can add 10 paths or fewer')
  })

  it('accepts a valid path', () => {
    expect(specPathError('src/main/resources/openapi.yml', PATHS)).toBeUndefined()
  })
})
