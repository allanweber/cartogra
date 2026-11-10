import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it } from 'vitest'

import { PermissionGuard } from '#/components/PermissionGuard'
import { Button } from '#/components/ui/button'
import { TooltipProvider } from '#/components/ui/tooltip'
import { useAuthStore } from '#/stores/useAuthStore'

function signInAs(roles: string[]) {
  useAuthStore.getState().hydrateWith({
    id: 'u1',
    email: '',
    name: null,
    authProvider: 'local',
    tenantId: 't1',
    roles,
  })
}

function renderGuarded() {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <TooltipProvider>
        <PermissionGuard action="service.create">
          <Button>Register service</Button>
        </PermissionGuard>
      </TooltipProvider>
    </QueryClientProvider>,
  )
}

describe('PermissionGuard', () => {
  afterEach(() => useAuthStore.getState().clearAuth())

  it('leaves the control untouched when the action is allowed', () => {
    signInAs(['MEMBER'])
    renderGuarded()
    expect(screen.getByRole('button', { name: /register service/i })).toBeEnabled()
    expect(screen.queryByTestId('read-only-guard')).not.toBeInTheDocument()
  })

  it('keeps the control visible but disabled for a guest', () => {
    signInAs(['GUEST'])
    renderGuarded()
    expect(screen.getByRole('button', { name: /register service/i })).toBeDisabled()
    expect(screen.getByTestId('read-only-guard')).toBeInTheDocument()
  })

  it('hides the control from a VIEWER, who the backend would refuse', () => {
    signInAs(['VIEWER'])
    renderGuarded()
    expect(screen.queryByRole('button', { name: /register service/i })).not.toBeInTheDocument()
  })
})
