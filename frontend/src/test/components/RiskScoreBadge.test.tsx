import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { RiskScoreBadge } from '#/components/RiskScoreBadge'

import type { RegistryService } from '#/lib/registry-types'

function service(overrides: Partial<RegistryService> = {}): RegistryService {
  return {
    id: 'svc-1',
    tenantId: 't1',
    name: 'payments-api',
    description: null,
    teamId: null,
    repositoryUrl: null,
    techStack: null,
    metadata: null,
    healthStatus: 'HEALTHY',
    lastDeployedAt: null,
    createdAt: '2024-01-01T00:00:00Z',
    updatedAt: '2024-01-01T00:00:00Z',
    externalId: null,
    connectionId: null,
    source: null,
    repositoryRef: null,
    k8sCluster: null,
    k8sNamespace: null,
    k8sDeployment: null,
    healthEndpoint: null,
    lastCommitAt: null,
    lastCommitSha: null,
    healthCheckedAt: null,
    tier: null,
    tags: null,
    slaTarget: null,
    documentationUrl: null,
    runbookUrl: null,
    ...overrides,
  }
}

describe('RiskScoreBadge', () => {
  it.each(['inline', 'bar', 'ring'] as const)('exposes a %s trigger with an aria-label and a breakdown popover', async (variant) => {
    render(<RiskScoreBadge service={service()} variant={variant} />)
    const trigger = screen.getByRole('button', { name: /risk score \d+, .+ risk\. view breakdown\./i })
    fireEvent.click(trigger)
    expect(await screen.findByText('No owning team')).toBeInTheDocument()
    expect(screen.getByText('Never deployed')).toBeInTheDocument()
  })

  it('renders the bar variant\'s progress width from the computed score', () => {
    const { container } = render(<RiskScoreBadge service={service()} variant="bar" />)
    const trigger = screen.getByRole('button', { name: /risk score (\d+)/i })
    const score = Number(/risk score (\d+)/i.exec(trigger.getAttribute('aria-label') ?? '')?.[1])
    const fill = container.querySelector<HTMLElement>('.bg-critical, .bg-warning, .bg-success')
    expect(fill?.style.width).toBe(`${score}%`)
  })

  it('marks its trigger with data-slot="popover-trigger" so an ancestor card/row Link can guard against it', () => {
    render(<RiskScoreBadge service={service()} variant="inline" />)
    expect(screen.getByRole('button', { name: /risk score/i })).toHaveAttribute('data-slot', 'popover-trigger')
  })
})
