import type { PageResult } from '#/lib/registry-types'

export type AuditActorKind = 'user' | 'system'

export interface AuditEvent {
  eventId: string
  entityType: string
  entityId: string
  action: string
  actorKind: AuditActorKind
  actorId: string | null
  actorName: string | null
  payload: Record<string, unknown>
  occurredAt: string
}

export type AuditEventsPage = PageResult<AuditEvent>
