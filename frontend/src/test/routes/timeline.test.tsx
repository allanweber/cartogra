import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Route } from '#/routes/_authenticated/timeline'
import { apiFetch } from '#/lib/api'

import type { AuditEvent, AuditEventsPage } from '#/lib/audit-types'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
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

vi.mock('#/components/AppLayout', () => ({
  AppLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

function makeEvent(i: number): AuditEvent {
  return {
    eventId: `e-${i}`,
    entityType: 'service',
    entityId: `s-${i}`,
    action: 'created',
    actorKind: 'system',
    actorId: null,
    actorName: null,
    payload: { name: `svc-${i}` },
    occurredAt: '2026-11-10T10:00:00Z',
  }
}

function renderPage(page: AuditEventsPage) {
  vi.mocked(apiFetch).mockResolvedValue(page)
  const Page = (Route as unknown as { component: React.ComponentType }).component
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('TimelinePage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('says a search with no match only covered the loaded events when more exist', async () => {
    renderPage({ items: [makeEvent(1), makeEvent(2)], total: 178, limit: 20, offset: 0 })
    expect(await screen.findAllByTestId('timeline-event')).toHaveLength(2)

    fireEvent.change(screen.getByLabelText(/search timeline events/i), { target: { value: 'nothing-here' } })

    expect(screen.getByText(/no match in the 2 loaded of 178 events/i)).toBeInTheDocument()
  })

  it('says no events match when every event is loaded', async () => {
    renderPage({ items: [makeEvent(1)], total: 1, limit: 20, offset: 0 })
    expect(await screen.findAllByTestId('timeline-event')).toHaveLength(1)

    fireEvent.change(screen.getByLabelText(/search timeline events/i), { target: { value: 'nothing-here' } })

    expect(screen.getByText('No events match this search.')).toBeInTheDocument()
  })
})
