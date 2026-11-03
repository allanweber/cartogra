import { describe, expect, it } from 'vitest'

import { resolvePermission } from '#/lib/permissions'

const TEAM = 'team-1'
const subject = (roles: string[], myTeamIds: string[] = []) => ({ roles, myTeamIds })

describe('resolvePermission', () => {
  it('guest is read-only for every action', () => {
    const guest = subject(['GUEST'])
    expect(resolvePermission(guest, 'service.edit', { teamId: TEAM })).toBe('readOnly')
    expect(resolvePermission(guest, 'team.create')).toBe('readOnly')
    expect(resolvePermission(guest, 'team.manage', { teamId: TEAM })).toBe('readOnly')
  })

  it('admin is allowed everything regardless of membership', () => {
    const admin = subject(['ADMIN'])
    expect(resolvePermission(admin, 'service.edit', { teamId: TEAM })).toBe('allow')
    expect(resolvePermission(admin, 'service.edit', { teamId: null })).toBe('allow')
    expect(resolvePermission(admin, 'team.create')).toBe('allow')
    expect(resolvePermission(admin, 'team.manage', { teamId: TEAM })).toBe('allow')
  })

  it('service.edit requires membership of the owning team', () => {
    expect(resolvePermission(subject(['MEMBER'], [TEAM]), 'service.edit', { teamId: TEAM })).toBe('allow')
    expect(resolvePermission(subject(['MEMBER'], ['other']), 'service.edit', { teamId: TEAM })).toBe('deny')
    expect(resolvePermission(subject(['MEMBER'], [TEAM]), 'service.edit', { teamId: null })).toBe('deny')
  })

  it('team.create requires TEAM_OWNER', () => {
    expect(resolvePermission(subject(['TEAM_OWNER']), 'team.create')).toBe('allow')
    expect(resolvePermission(subject(['MEMBER']), 'team.create')).toBe('deny')
    expect(resolvePermission(subject(['VIEWER']), 'team.create')).toBe('deny')
  })

  it('team.manage requires TEAM_OWNER of that team', () => {
    expect(resolvePermission(subject(['TEAM_OWNER'], [TEAM]), 'team.manage', { teamId: TEAM })).toBe('allow')
    expect(resolvePermission(subject(['TEAM_OWNER'], ['other']), 'team.manage', { teamId: TEAM })).toBe('deny')
    expect(resolvePermission(subject(['MEMBER'], [TEAM]), 'team.manage', { teamId: TEAM })).toBe('deny')
  })
})

describe('settings.view', () => {
  it('is hidden from guests and open to every other role', () => {
    expect(resolvePermission(subject(['GUEST']), 'settings.view')).toBe('deny')
    expect(resolvePermission(subject(['VIEWER']), 'settings.view')).toBe('allow')
    expect(resolvePermission(subject(['ADMIN']), 'settings.view')).toBe('allow')
  })
})
