import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ContractConsumers } from '#/components/ContractConsumers'
import { ApiError, apiFetch } from '#/lib/api'

import type { ContractConsumer } from '#/lib/contract-types'

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  Link: ({ children, params, className }: { children: React.ReactNode; params: { serviceId: string }; className?: string }) => (
    <a href={`/catalog/${params.serviceId}`} className={className}>
      {children}
    </a>
  ),
}))

vi.mock('#/lib/api', () => ({
  apiFetch: vi.fn(),
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

function consumer(overrides: Partial<ContractConsumer>): ContractConsumer {
  return {
    serviceId: 'svc-1-aaaaaaaa',
    serviceName: 'payments-service',
    dependencyTypes: ['declared'],
    protocols: ['http'],
    ...overrides,
  }
}

function renderCard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <ContractConsumers contractId="c1" />
    </QueryClientProvider>,
  )
}

describe('ContractConsumers', () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset()
  })

  it('lists each consumer with its count, badges and a link to its catalog page', async () => {
    vi.mocked(apiFetch).mockResolvedValue({
      items: [
        consumer({ serviceId: 'svc-1', serviceName: 'fraud-service', dependencyTypes: ['declared', 'observed'] }),
        consumer({ serviceId: 'svc-2', serviceName: null, protocols: ['grpc', 'http'] }),
      ],
      total: 2,
      limit: 50,
      offset: 0,
    })

    renderCard()

    const list = await screen.findByRole('list', { name: 'Consumers' })
    const links = within(list).getAllByRole('link')
    expect(links[0]).toHaveAttribute('href', '/catalog/svc-1')
    expect(within(links[0]).getByText('fraud-service')).toBeInTheDocument()
    expect(within(links[0]).getByText('declared')).toBeInTheDocument()
    expect(within(links[0]).getByText('observed')).toBeInTheDocument()
    expect(within(links[1]).getByText('Unknown service svc-2')).toBeInTheDocument()
    expect(within(links[1]).getByText('grpc')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/v1/contracts/c1/consumers?limit=50&offset=0')
  })

  it('says so when no service depends on the contract', async () => {
    vi.mocked(apiFetch).mockResolvedValue({ items: [], total: 0, limit: 50, offset: 0 })

    renderCard()

    expect(await screen.findByText('No services depend on this one.')).toBeInTheDocument()
  })

  it('shows skeletons while loading', () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}))

    const { container } = renderCard()

    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBe(3)
  })

  it('shows the error with its trace id', async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Boom', 'abc123'))

    renderCard()

    expect(await screen.findByText(/Boom/)).toHaveTextContent('Boom (trace: abc123)')
  })

  it('loads the next page on Show more', async () => {
    vi.mocked(apiFetch)
      .mockResolvedValueOnce({ items: [consumer({ serviceId: 'svc-1', serviceName: 'a' })], total: 2, limit: 50, offset: 0 })
      .mockResolvedValueOnce({ items: [consumer({ serviceId: 'svc-2', serviceName: 'b' })], total: 2, limit: 50, offset: 1 })

    renderCard()

    fireEvent.click(await screen.findByRole('button', { name: 'Show more' }))

    await waitFor(() => expect(screen.getByText('b')).toBeInTheDocument())
    expect(apiFetch).toHaveBeenLastCalledWith('/v1/contracts/c1/consumers?limit=50&offset=1')
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
  })
})
