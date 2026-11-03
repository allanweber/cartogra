import { useQuery } from '@tanstack/react-query'

import { apiFetch } from '#/lib/api'
import { GUEST_ROLE, isGuestUser } from '#/lib/guest'
import { useAuthStore } from '#/stores/useAuthStore'
import type { AuthUser } from '#/stores/useAuthStore'

/** `readOnly`: render the control, disabled (guest demo). `deny`: don't render it. */
export type Permission = 'allow' | 'readOnly' | 'deny'

export type Action = 'service.edit' | 'team.create' | 'team.manage' | 'settings.view'

export const READ_ONLY_MESSAGE = 'Read-only demo — changes are disabled'

export interface PermissionContext {
  teamId?: string | null
}

interface Subject {
  roles: readonly string[]
  myTeamIds: readonly string[]
}

const allowIf = (condition: boolean): Permission => (condition ? 'allow' : 'deny')

export function resolvePermission(subject: Subject, action: Action, ctx: PermissionContext = {}): Permission {
  const has = (role: string) => subject.roles.includes(role)
  if (has(GUEST_ROLE)) return action === 'settings.view' ? 'deny' : 'readOnly'
  if (has('ADMIN')) return 'allow'

  const isMemberOf = !!ctx.teamId && subject.myTeamIds.includes(ctx.teamId)
  switch (action) {
    case 'settings.view':
      return 'allow'
    case 'service.edit':
      return allowIf(isMemberOf)
    case 'team.create':
      return allowIf(has('TEAM_OWNER'))
    case 'team.manage':
      return allowIf(has('TEAM_OWNER') && isMemberOf)
  }
}

function needsTeamMembership(user: AuthUser | null): boolean {
  return !!user && !user.roles.includes('ADMIN') && !isGuestUser(user)
}

/** Returns a checker bound to the signed-in user; fetches the user's team ids when a rule may need them. */
export function useCan(): (action: Action, ctx?: PermissionContext) => Permission {
  const user = useAuthStore((s) => s.user)
  const { data: myTeamIds } = useQuery({
    queryKey: ['teams', 'mine'],
    queryFn: () => apiFetch<string[]>('/v1/registry/teams/mine'),
    enabled: needsTeamMembership(user),
  })
  const subject: Subject = { roles: user?.roles ?? [], myTeamIds: myTeamIds ?? [] }
  return (action, ctx) => resolvePermission(subject, action, ctx)
}

/** For route guards outside React: rules that need team membership resolve as non-member. */
export function canUser(user: AuthUser | null | undefined, action: Action, ctx?: PermissionContext): Permission {
  return resolvePermission({ roles: user?.roles ?? [], myTeamIds: [] }, action, ctx)
}

/** True when the session may look but not change anything (guest demo). */
export function useIsReadOnly(): boolean {
  return useAuthStore((s) => isGuestUser(s.user))
}
