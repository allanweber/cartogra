import { describe, expect, it, vi } from 'vitest'
import { CallScheduler } from '../app/calls.ts'

const make = (invoke = vi.fn(async () => {})) =>
  new CallScheduler({ calls: [{ to: 'a', enabled: true }, { to: 'b', enabled: false }], intervalMs: 1000, invoke })

describe('CallScheduler', () => {
  it('calls only enabled downstreams', async () => {
    const invoke = vi.fn(async () => {})
    await make(invoke).tick()
    expect(invoke).toHaveBeenCalledTimes(1)
    expect(invoke).toHaveBeenCalledWith('a')
  })

  it('enables and disables a dormant call at runtime', async () => {
    const invoke = vi.fn(async () => {})
    const scheduler = make(invoke)
    expect(scheduler.setEnabled('b', true)).toBe(true)
    await scheduler.tick()
    expect(invoke).toHaveBeenCalledTimes(2)
    scheduler.setEnabled('b', false)
    invoke.mockClear()
    await scheduler.tick()
    expect(invoke).toHaveBeenCalledTimes(1)
  })

  it('rejects unknown targets', () => {
    expect(make().setEnabled('zzz', true)).toBe(false)
  })

  it('reports failures without stopping other calls', async () => {
    const onError = vi.fn()
    const invoke = vi.fn(async (t: string) => { if (t === 'a') throw new Error('down') })
    const scheduler = new CallScheduler({ calls: [{ to: 'a', enabled: true }, { to: 'b', enabled: true }], intervalMs: 1000, invoke, onError })
    await scheduler.tick()
    expect(onError).toHaveBeenCalledWith('a', expect.any(Error))
    expect(invoke).toHaveBeenCalledTimes(2)
  })
})
