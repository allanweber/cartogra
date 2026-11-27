import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'

import { NotificationAttemptsCard } from '#/components/NotificationAttemptsCard'
import { NotificationRuleCard } from '#/components/NotificationRuleCard'
import { apiFetch, apiMutate } from '#/lib/api'
import type { NotificationAttempt, NotificationRule } from '#/lib/contract-types'
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

const ATTEMPTS_PATH = '/v1/contracts/notification-rules/rule-1/attempts'
const TEST_PATH = '/v1/contracts/notification-rules/rule-1/test'
const MASKED_SLACK = 'https://hooks.slack.com/services/T***/B***/token'
const MASKED_TEAMS = 'https://outlook.office.com/webhook/teams-***'
const MASKED_EMAIL = 'a***@example.com'

const RULE: NotificationRule = {
  id: 'rule-1',
  serviceId: null,
  enabled: true,
  slackWebhookUrl: MASKED_SLACK,
  teamsWebhookUrl: MASKED_TEAMS,
  emailAddresses: [MASKED_EMAIL],
  createdAt: '2026-11-01T10:00:00Z',
  updatedAt: '2026-11-02T10:00:00Z',
}

function attempt(
  id: string,
  channel: NotificationAttempt['channel'],
  destinationHint: string,
  outcome: NotificationAttempt['outcome'],
  createdAt: string,
  errorCode: string | null = null,
  statusCode: number | null = 200,
): NotificationAttempt {
  return {
    id,
    ruleId: 'rule-1',
    checkId: null,
    attempt: 1,
    channel,
    destinationHint,
    waitMs: 0,
    statusCode,
    errorCode,
    outcome,
    createdAt,
    finishedAt: createdAt,
  }
}

const ATTEMPTS: NotificationAttempt[] = [
  attempt('a2', 'email', MASKED_EMAIL, 'sent', '2026-11-03T10:01:00Z'),
  attempt('a1', 'slack', MASKED_SLACK, 'failed', '2026-11-03T10:00:00Z', 'HTTP_500', 500),
]

function setRoles(roles: string[]) {
  useAuthStore.setState({
    user: {
      id: 'u1',
      email: 'admin@example.com',
      name: null,
      authProvider: 'local',
      tenantId: 't1',
      roles,
    },
    isAuthenticated: true,
  })
}

function renderCard(rule: NotificationRule = RULE) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <NotificationAttemptsCard rule={rule} />
    </QueryClientProvider>,
  )
}

function renderRuleCard(rule: NotificationRule = RULE) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <NotificationRuleCard rule={rule} />
    </QueryClientProvider>,
  )
}

describe('NotificationAttemptsCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setRoles(['ADMIN'])
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path === ATTEMPTS_PATH) {
        return Promise.resolve({ items: ATTEMPTS, total: 2, limit: 50, offset: 0 })
      }
      return Promise.resolve([])
    })
  })

  it('showsAttemptsAndTestOutcomes', async () => {
    const testOutcomes = [
      attempt('t1', 'teams', MASKED_TEAMS, 'failed', '2026-11-03T10:02:00Z', 'TIMEOUT', null),
      attempt('t2', 'email', MASKED_EMAIL, 'sent', '2026-11-03T10:02:01Z'),
    ]
    vi.mocked(apiMutate).mockResolvedValue(testOutcomes)
    renderCard()

    const history = await screen.findByRole('list', { name: 'Delivery attempts' })
    const rows = within(history).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent(MASKED_EMAIL)
    expect(rows[0]).toHaveTextContent('sent')
    expect(rows[1]).toHaveTextContent(MASKED_SLACK)
    expect(rows[1]).toHaveTextContent('failed')
    expect(apiFetch).toHaveBeenCalledWith(ATTEMPTS_PATH)

    fireEvent.click(screen.getByRole('button', { name: 'Test rule' }))
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith(TEST_PATH, {}),
    )

    const outcomes = await screen.findByRole('list', { name: 'Test outcomes' })
    expect(within(outcomes).getAllByRole('listitem')).toHaveLength(2)
    expect(within(outcomes).getByText(MASKED_TEAMS)).toBeInTheDocument()
    expect(within(outcomes).getByText('Error: TIMEOUT')).toBeInTheDocument()
    expect(within(outcomes).getByText(MASKED_EMAIL)).toBeInTheDocument()
    expect(screen.getByText(/Delivery failed: teams TIMEOUT/)).toBeInTheDocument()
    expect(vi.mocked(toast.error)).toHaveBeenCalledWith('Test finished with failed attempts')
    expect(vi.mocked(toast.success)).not.toHaveBeenCalled()
    expect(document.body.textContent).not.toContain('outlook.office.com/webhook/raw')
  })

  it('shows a success toast when every test attempt succeeds', async () => {
    vi.mocked(apiMutate).mockResolvedValue([
      attempt('t1', 'email', MASKED_EMAIL, 'sent', '2026-11-03T10:02:01Z'),
    ])
    renderCard()

    fireEvent.click(screen.getByRole('button', { name: 'Test rule' }))
    await screen.findByRole('list', { name: 'Test outcomes' })

    expect(vi.mocked(toast.success)).toHaveBeenCalledWith('Test finished')
    expect(vi.mocked(toast.error)).not.toHaveBeenCalled()
    expect(screen.queryByText(/Delivery failed/)).not.toBeInTheDocument()
  })

  it('disables the test button for a paused rule', async () => {
    renderCard({ ...RULE, enabled: false })

    expect(await screen.findByRole('button', { name: 'Test rule' })).toBeDisabled()
    expect(screen.getByText('Enable the rule to run a test.')).toBeInTheDocument()
    expect(apiMutate).not.toHaveBeenCalled()
  })

  it('shows an empty state when the rule has no attempts', async () => {
    vi.mocked(apiFetch).mockResolvedValue({ items: [], total: 0, limit: 50, offset: 0 })
    renderCard()

    expect(await screen.findByText('No attempts yet.')).toBeInTheDocument()
  })

  it('shows the trace id when the test fails', async () => {
    const { ApiError } = await import('#/lib/api')
    vi.mocked(apiMutate).mockRejectedValue(new ApiError('VALIDATION_ERROR', 'Rule is disabled.', 'trace-9'))
    renderCard()

    fireEvent.click(await screen.findByRole('button', { name: 'Test rule' }))
    expect(await screen.findByText(/Rule is disabled/)).toHaveTextContent('(trace: trace-9)')
  })

  it.each([
    ['pending', 'text-info'],
    ['sent', 'text-success'],
    ['failed', 'text-critical'],
  ] as const)('shows a %s attempt with the %s class', async (outcome, color) => {
    vi.mocked(apiFetch).mockResolvedValue({
      items: [attempt('a1', 'slack', MASKED_SLACK, outcome, '2026-11-03T10:00:00Z')],
      total: 1,
      limit: 50,
      offset: 0,
    })
    renderCard()

    const history = await screen.findByRole('list', { name: 'Delivery attempts' })
    const badge = within(history).getByText(outcome)
    expect(badge).toHaveAttribute('data-variant', 'outline')
    expect(badge).toHaveClass('border-current', color)
    expect(badge.closest('li')).toHaveClass('px-3')
  })

  it.each([
    ['enabled', 'text-success', true],
    ['paused', 'text-warning', false],
  ] as const)('shows the %s rule badge with the %s class', (label, color, enabled) => {
    renderRuleCard({ ...RULE, enabled })

    const badge = screen.getByText(label)
    expect(badge).toHaveAttribute('data-variant', 'outline')
    expect(badge).toHaveClass('border-current', color)
    expect(badge.closest('[data-slot="card-action"]')).not.toBeNull()
  })
})
