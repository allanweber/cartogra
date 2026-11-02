import type { AuditEvent } from '#/lib/audit-types'

export type AuditCategory = 'service' | 'team' | 'dependency' | 'drift' | 'integration' | 'access'

/** Backend entity types behind each Timeline filter chip. */
export const AUDIT_CATEGORY_ENTITY_TYPES: Record<AuditCategory, string[]> = {
  service: ['service'],
  team: ['team'],
  dependency: ['dependency'],
  drift: ['drift'],
  integration: ['scm_connection', 'cluster'],
  access: ['user', 'invitation', 'oidc_config'],
}

const ENTITY_LABELS: Record<string, string> = {
  service: 'Service',
  team: 'Team',
  dependency: 'Dependency',
  drift: 'Drift',
  scm_connection: 'SCM connection',
  cluster: 'Cluster',
  user: 'User',
  invitation: 'Invitation',
  oidc_config: 'OIDC config',
}

const ACTION_LABELS: Record<string, string> = {
  created: 'created',
  updated: 'updated',
  deleted: 'deleted',
  removed: 'removed',
  discovered: 'discovered',
  discovery_merged: 'updated by discovery',
  owner_changed: 'ownership changed',
  member_added: 'member added',
  member_removed: 'member removed',
  declared: 'declared',
  observed: 'first observed in traffic',
  detected: 'detected',
  resolved: 'resolved',
  sync_completed: 'sync completed',
  sync_failed: 'sync failed',
  invited: 'sent',
  role_changed: 'role changed',
  disabled: 'disabled',
  enabled: 'enabled',
}

export function auditCategory(entityType: string): AuditCategory | null {
  for (const [category, types] of Object.entries(AUDIT_CATEGORY_ENTITY_TYPES)) {
    if (types.includes(entityType)) return category as AuditCategory
  }
  return null
}

function str(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null
}

/** What the event is about: a name from the payload, or a short id when the payload carries none. */
export function auditSubject(event: AuditEvent): string {
  const p = event.payload
  const source = str(p.sourceName)
  const target = str(p.targetName)
  if (source || target) return `${source ?? 'unknown'} → ${target ?? 'unknown'}`
  return str(p.name) ?? str(p.email) ?? str(p.provider) ?? str(p.discoveryUri) ?? event.entityId.slice(0, 8)
}

export function auditMessage(event: AuditEvent): string {
  const entity = ENTITY_LABELS[event.entityType] ?? event.entityType
  const action = ACTION_LABELS[event.action] ?? event.action.replaceAll('_', ' ')
  const changes = event.payload.changes
  const changed =
    changes && typeof changes === 'object' ? Object.keys(changes) : []
  const detail = changed.length > 0 ? `: ${changed.join(', ')}` : ''
  const error = event.action === 'sync_failed' ? str(event.payload.error) : null
  return `${entity} ${action}${detail}${error ? ` — ${error}` : ''}`
}

export function auditActor(event: AuditEvent): string {
  if (event.actorKind === 'system') return 'system'
  return event.actorName ?? (event.actorId ? `user ${event.actorId.slice(0, 8)}` : 'unknown user')
}

export function relativeTime(iso: string, now: number = Date.now()): string {
  const mins = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}
