import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiKeyCreateDialog } from '#/components/ApiKeyCreateDialog'

const mutate = vi.fn()

vi.mock('#/hooks/useApiKeys', () => ({
  useCreateApiKey: () => ({ mutate, reset: vi.fn(), isPending: false, error: null }),
}))

describe('ApiKeyCreateDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 24, 23, 30, 0))
    mutate.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  function renderDialog() {
    render(<ApiKeyCreateDialog open onOpenChange={() => {}} onCreated={() => {}} />)
  }

  it('the date input has min today', () => {
    renderDialog()
    expect(screen.getByLabelText(/Expires/)).toHaveAttribute('min', '2026-11-24')
  })

  it('the expiry is the end of the local day', () => {
    renderDialog()
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'github-ci' } })
    fireEvent.change(screen.getByLabelText(/Expires/), { target: { value: '2026-12-31' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create key' }))

    expect(mutate).toHaveBeenCalledTimes(1)
    const { expiresAt } = mutate.mock.calls[0][0]
    expect(expiresAt).toBe(new Date(2026, 11, 31, 23, 59, 59, 999).toISOString())
    const parsed = new Date(expiresAt)
    expect(parsed.getDate()).toBe(31)
    expect(parsed.getHours()).toBe(23)
    expect(parsed.getMilliseconds()).toBe(999)
  })
})
