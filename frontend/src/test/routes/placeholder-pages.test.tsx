import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Route as ApiKeys } from '#/routes/_authenticated/settings.api-keys'
import { Route as Intelligence } from '#/routes/_authenticated/intelligence'
import { Route as Notifications } from '#/routes/_authenticated/settings.notifications'
import { Route as Operations } from '#/routes/_authenticated/ops'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
  Link: ({ children }: { children: React.ReactNode }) => <a href="/">{children}</a>,
}))

vi.mock('#/components/AppLayout', () => ({ AppLayout: ({ underDevelopment }: { underDevelopment?: string }) => <div data-testid="under-development">{underDevelopment}</div> }))
vi.mock('#/components/SettingsTabsLayout', () => ({ SettingsTabsLayout: ({ underDevelopment }: { underDevelopment?: string }) => <div data-testid="under-development">{underDevelopment}</div> }))

describe('placeholder pages', () => {
  it.each([
    ['Intelligence', Intelligence],
    ['Operations', Operations],
    ['API keys', ApiKeys],
    ['Notifications', Notifications],
  ])('%s declares itself a placeholder', (_, route) => {
    const Page = (route as unknown as { component: React.ComponentType }).component
    render(<Page />)
    expect(screen.getByTestId('under-development')).toHaveTextContent('placeholder')
  })
})
