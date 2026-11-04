import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { Route } from '#/routes/_authenticated/contracts'

vi.mock('@tanstack/react-router', async () => ({
  ...await vi.importActual('@tanstack/react-router'),
  createFileRoute: () => (opts: Record<string, unknown>) => opts,
}))

vi.mock('#/components/AppLayout', () => ({
  AppLayout: ({ underDevelopment, children }: { underDevelopment?: string; children: React.ReactNode }) => (
    <div>
      <div data-testid="under-development">{underDevelopment}</div>
      {children}
    </div>
  ),
}))

describe('ContractsPage', () => {
  it('declares its data as mock', () => {
    const Page = (Route as unknown as { component: React.ComponentType }).component
    render(<Page />)
    expect(screen.getByTestId('under-development')).toHaveTextContent('mock')
  })
})
