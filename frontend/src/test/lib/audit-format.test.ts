import { describe, expect, it } from 'vitest'

import { auditActor, auditCategory, auditMessage, auditSubject, relativeTime } from '#/lib/audit-format'

import type { AuditEvent } from '#/lib/audit-types'

function event(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    eventId: 'e1',
    entityType: 'service',
    entityId: '0123456789abcdef',
    action: 'created',
    actorKind: 'user',
    actorId: 'abcdef0123456789',
    actorName: 'Alice',
    payload: { name: 'checkout-api' },
    occurredAt: '2026-10-29T10:00:00Z',
    ...overrides,
  }
}

describe('audit-format', () => {
  it('names the subject from the payload, edges as source → target', () => {
    expect(auditSubject(event())).toBe('checkout-api')
    expect(
      auditSubject(event({ entityType: 'dependency', payload: { sourceName: 'web', targetName: 'api' } })),
    ).toBe('web → api')
    expect(auditSubject(event({ payload: {} }))).toBe('01234567')
  })

  it('lists changed fields and sync errors in the message', () => {
    expect(auditMessage(event({ action: 'updated', payload: { changes: { tier: {}, tags: {} } } }))).toBe(
      'Service updated: tier, tags',
    )
    expect(
      auditMessage(event({ entityType: 'scm_connection', action: 'sync_failed', payload: { error: '401' } })),
    ).toBe('SCM connection sync failed — 401')
  })

  it('shows system, the actor name, or a short user id', () => {
    expect(auditActor(event({ actorKind: 'system', actorId: null, actorName: null }))).toBe('system')
    expect(auditActor(event())).toBe('Alice')
    expect(auditActor(event({ actorName: null }))).toBe('user abcdef01')
  })

  it('maps entity types to filter categories', () => {
    expect(auditCategory('cluster')).toBe('integration')
    expect(auditCategory('oidc_config')).toBe('access')
    expect(auditCategory('unknown')).toBeNull()
  })

  it('formats relative time', () => {
    const now = new Date('2026-10-29T12:00:00Z').getTime()
    expect(relativeTime('2026-10-29T11:59:50Z', now)).toBe('just now')
    expect(relativeTime('2026-10-29T10:00:00Z', now)).toBe('2h ago')
  })
})
