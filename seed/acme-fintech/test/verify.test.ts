import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { loadSeedFile } from '../src/schema.ts'
import { godService, verify } from '../src/verify.ts'
import { FakeApi } from './fakeApi.ts'

const seed = loadSeedFile(resolve(import.meta.dirname, '../seed-data.json'))

describe('verify', () => {
  it('identifies the god service', () => {
    expect(godService(seed)).toBe('ledger-service')
  })

  it('passes the load-twice checks against a consistent API', async () => {
    const checks = await verify(new FakeApi(seed), seed, { skipObserved: true, sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50 })
    expect(checks.filter((c) => !c.ok)).toEqual([])
  })
})

describe('verify with drift', () => {
  const sleep = async () => {}
  const opts = { sleep, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50, observedTimeoutMs: 0, driftTimeoutMs: 0 }

  it('reports drift records that never appear', async () => {
    const api = new FakeApi(seed)
    const checks = await verify(api, seed, { ...opts, skipObserved: true, withDrift: { enable: async () => {}, disable: async () => {} } })
    expect(checks.find((c) => c.name === '4 drift records raised')?.ok).toBe(false)
  })

  it('passes when 3 undeclared and 1 missing drift records exist', async () => {
    const api = new FakeApi(seed)
    api.driftRecords = [
      ['UNDECLARED', 'reporting-service', 'payments-service'],
      ['UNDECLARED', 'webhook-relay', 'fraud-service'],
      ['UNDECLARED', 'legacy-batch', 'notification-service'],
      ['MISSING', 'loyalty-service', 'fx-service'],
    ].map(([type, from, to]) => ({ type: type as 'UNDECLARED' | 'MISSING', sourceServiceId: `svc-${from}`, targetServiceId: `svc-${to}` }))
    const checks = await verify(api, seed, { ...opts, skipObserved: true, withDrift: { enable: async () => {}, disable: async () => {} } })
    expect(checks.find((c) => c.name === '4 drift records raised')?.ok).toBe(true)
  })
})
