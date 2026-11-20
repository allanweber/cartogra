import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/_authenticated/settings.scm'

vi.mock('#/components/SettingsTabsLayout', () => ({
  SettingsTabsLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

vi.mock('#/components/SpecPathsCard', () => ({
  SpecPathsCard: () => <div data-testid="spec-paths-card" />,
}))

vi.mock('@tanstack/react-router', async () => ({
  ...(await vi.importActual('@tanstack/react-router')),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

describe('SCM Configuration settings page', () => {
  it('shows the spec discovery card', () => {
    const Page = (Route as any).component
    render(<Page />)
    expect(screen.getByText('SCM Configuration')).toBeInTheDocument()
    expect(screen.getByTestId('spec-paths-card')).toBeInTheDocument()
  })
})
