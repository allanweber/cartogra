import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiFetch, apiMutate } from '#/lib/api'
import type { NotificationRule } from '#/lib/contract-types'
import { Route } from '#/routes/_authenticated/settings.notifications'
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

vi.mock('#/components/SettingsTabsLayout', () => ({
  SettingsTabsLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

const RULES_PATH = '/v1/contracts/notification-rules?limit=20&offset=0'
const RAW_SLACK = 'https://hooks.slack.com/services/T123/B456/rawSecretToken'
const RAW_EMAIL = 'alice@example.com'
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

function mockRules(rules: NotificationRule[]) {
  vi.mocked(apiFetch).mockImplementation((path: string) => {
    if (path === RULES_PATH) {
      return Promise.resolve({ items: rules, total: rules.length, limit: 20, offset: 0 })
    }
    return Promise.resolve([])
  })
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const Page = (Route as unknown as { component: React.ComponentType }).component
  return render(
    <QueryClientProvider client={client}>
      <Page />
    </QueryClientProvider>,
  )
}

describe('Notifications settings page', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setRoles(['ADMIN'])
  })

  it('rendersRulesAndMasksValuesFromApi', async () => {
    mockRules([RULE])
    renderPage()

    expect(await screen.findByText('Tenant-wide rule')).toBeInTheDocument()
    expect(screen.getByText(MASKED_SLACK)).toBeInTheDocument()
    expect(screen.getByText(MASKED_TEAMS)).toBeInTheDocument()
    expect(screen.getByText(MASKED_EMAIL)).toBeInTheDocument()
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(RULES_PATH)
    expect(document.body.textContent).not.toContain(RAW_SLACK)
    expect(document.body.textContent).not.toContain(RAW_EMAIL)
  })

  it('creates a rule and shows the masked response only', async () => {
    let rules: NotificationRule[] = []
    vi.mocked(apiFetch).mockImplementation((path: string) => {
      if (path === RULES_PATH) {
        return Promise.resolve({ items: rules, total: rules.length, limit: 20, offset: 0 })
      }
      return Promise.resolve([])
    })
    vi.mocked(apiMutate).mockImplementation(() => {
      rules = [RULE]
      return Promise.resolve(RULE as never)
    })
    renderPage()
    expect(await screen.findByText('No notification rules yet.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Create rule' }))
    fireEvent.change(await screen.findByLabelText('Service ID (optional)'), {
      target: { value: 'svc-1' },
    })
    fireEvent.change(screen.getByLabelText('Slack webhook URL'), {
      target: { value: RAW_SLACK },
    })
    fireEvent.change(screen.getByLabelText('Email addresses'), {
      target: { value: RAW_EMAIL },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create rule' }))

    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith('/v1/contracts/notification-rules', {
        serviceId: 'svc-1',
        slackWebhookUrl: RAW_SLACK,
        teamsWebhookUrl: null,
        emailAddresses: [RAW_EMAIL],
      }),
    )
    expect(await screen.findByText(MASKED_SLACK)).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(RAW_SLACK)
    expect(document.body.textContent).not.toContain(RAW_EMAIL)
  })

  it('refuses a new rule without a destination', async () => {
    mockRules([])
    renderPage()
    expect(await screen.findByText('No notification rules yet.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Create rule' }))
    fireEvent.click(screen.getByRole('button', { name: 'Create rule' }))

    expect(await screen.findByText('Enter at least one destination.')).toBeInTheDocument()
    expect(apiMutate).not.toHaveBeenCalled()
  })

  it('pauses and deletes a rule', async () => {
    mockRules([RULE])
    vi.mocked(apiMutate).mockImplementation((_path: string, _body: unknown, method?: string) => {
      if (method === 'DELETE') return Promise.resolve(undefined as never)
      return Promise.resolve(RULE as never)
    })
    renderPage()
    expect(await screen.findByText('Tenant-wide rule')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Pause' }))
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith(
        '/v1/contracts/notification-rules/rule-1',
        { enabled: false },
        'PATCH',
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() =>
      expect(apiMutate).toHaveBeenCalledWith(
        '/v1/contracts/notification-rules/rule-1',
        undefined,
        'DELETE',
      ),
    )
  })

  it('hides write controls from a viewer', async () => {
    setRoles(['VIEWER'])
    mockRules([RULE])
    renderPage()

    expect(await screen.findByText('Tenant-wide rule')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create rule' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Pause' })).not.toBeInTheDocument()
  })

  it('shows the trace id when rules fail to load', async () => {
    const { ApiError } = await import('#/lib/api')
    vi.mocked(apiFetch).mockRejectedValue(new ApiError('INTERNAL_ERROR', 'Boom', 'trace-123'))
    renderPage()

    expect(await screen.findByText(/Boom/)).toHaveTextContent('(trace: trace-123)')
  })
})
