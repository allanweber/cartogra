import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'

import { ReadOnlyGuard } from '#/components/ReadOnlyGuard'
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
    <TooltipProvider>
      <ReadOnlyGuard>
        <Button>Register service</Button>
      </ReadOnlyGuard>
    </TooltipProvider>,
  )
}

describe('ReadOnlyGuard', () => {
  afterEach(() => useAuthStore.getState().clearAuth())

  it('leaves the control untouched for a regular user', () => {
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
})
