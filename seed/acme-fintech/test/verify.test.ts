import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { callProtocol, declaredCalls, loadSeedFile } from '../src/schema.ts'
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

describe('verify unresolved span services', () => {
  const opts = { sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50, observedTimeoutMs: 0 }
  const check = '1 external peers listed as unresolved'

  it('fails while the external peer is not listed', async () => {
    const checks = await verify(new FakeApi(seed), seed, opts)
    expect(checks.find((c) => c.name === check)).toMatchObject({ ok: false, detail: 'stripe-gateway' })
  })

  it('passes once the external peer is listed', async () => {
    const api = new FakeApi(seed)
    api.unresolved = [{ name: 'stripe-gateway', seenCount: 4 }]
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === check)?.ok).toBe(true)
  })
})

describe('verify kafka and db edges', () => {
  const opts = { sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50, observedTimeoutMs: 0 }

  it('fails while the kafka and db edges are not observed', async () => {
    const checks = await verify(new FakeApi(seed), seed, opts)
    expect(checks.find((c) => c.name === '2 kafka edges observed')?.ok).toBe(false)
    expect(checks.find((c) => c.name === '1 db edges observed')).toMatchObject({ ok: false, detail: 'ledger-service>ledger-db DB' })
  })

  it('passes once every declared call is observed with its protocol', async () => {
    const api = new FakeApi(seed)
    for (const c of declaredCalls(seed).filter((call) => call.enabled)) {
      api.observed.add(`svc-${c.from}>svc-${c.to} ${callProtocol(c)}`)
    }
    const checks = await verify(api, seed, opts)
    for (const name of ['declared calls observed from spans', '2 kafka edges observed', '1 db edges observed']) {
      expect(checks.find((c) => c.name === name)?.ok).toBe(true)
    }
  })

  it('does not accept an http edge in place of the kafka one', async () => {
    const api = new FakeApi(seed)
    api.observed.add('svc-payments-service>svc-notification-service HTTP')
    api.observed.add('svc-payments-service>svc-loyalty-service KAFKA')
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === '2 kafka edges observed')).toMatchObject({
      ok: false,
      detail: 'payments-service>notification-service KAFKA',
    })
  })
})
