import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { callProtocol, declaredCalls, loadSeedFile } from '../src/schema.ts'
import { loadSeed } from '../src/load.ts'
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

describe('verify contracts', () => {
  const opts = { skipObserved: true, sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50 }

  it('passes when ledger-service is at v2 and re-publishing stores nothing', async () => {
    const checks = await verify(new FakeApi(seed), seed, opts)
    expect(checks.filter((c) => c.name.startsWith('ledger-service') && /contract|diff|re-publishing/.test(c.name)).map((c) => [c.name, c.ok])).toEqual([
      ['ledger-service contract exists', true],
      ['ledger-service contract at v2', true],
      ['ledger-service contract listed with its service name', true],
      ['ledger-service v1 → v2 diff adds /ledgers/{id}/history', true],
      ['ledger-service re-publishing latest spec stores nothing', true],
      ['ledger-service contract lists 12 consumers', true],
    ])
    expect(checks.find((c) => c.name === 'ledger-service v2 is compatible (path-added)')?.ok).toBe(true)
  })

  it('passes the default verify after load --breaking and publishes no new version', async () => {
    const api = new FakeApi(seed)
    const loaded = await loadSeed(api, seed, { ...opts, breaking: true })
    const checks = await verify(api, seed, opts)
    expect(checks.filter((c) => !c.ok)).toEqual([])
    expect((await api.getContract(loaded.contracts['ledger-service'])).latestVersion.versionNumber).toBe(3)
  })

  it('passes when v2 is unchecked (stored before 5.3) and its live diff is compatible', async () => {
    const api = new FakeApi(seed)
    const diff = api.contractDiff.bind(api)
    api.contractDiff = async (id, v) => {
      const d = await diff(id, v)
      return { ...d, to: { ...d.to, breaking: null } }
    }
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service v2 is compatible (path-added)')?.ok).toBe(true)
  })

  it('fails when v2 is stored as breaking', async () => {
    const api = new FakeApi(seed)
    const diff = api.contractDiff.bind(api)
    api.contractDiff = async (id, v) => {
      const d = await diff(id, v)
      return { ...d, to: { ...d.to, breaking: true } }
    }
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service v2 is compatible (path-added)')?.ok).toBe(false)
  })

  it('fails when the live diff of v2 has no path-added', async () => {
    const api = new FakeApi(seed)
    const diff = api.contractDiff.bind(api)
    api.contractDiff = async (id, v) => {
      const d = await diff(id, v)
      return { ...d, compatibility: { isBreaking: false, changes: [] } }
    }
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service v2 is compatible (path-added)')?.ok).toBe(false)
  })

  it('with breaking, passes when v3 is breaking with both rules and listed by the status filter', async () => {
    const checks = await verify(new FakeApi(seed), seed, { ...opts, breaking: true })
    expect(checks.filter((c) => !c.ok)).toEqual([])
    expect(checks.filter((c) => c.name.startsWith('ledger-service')).map((c) => c.name)).toEqual(expect.arrayContaining([
      'ledger-service contract at v3',
      'ledger-service v2 is compatible (path-added)',
      'ledger-service v3 is breaking (path-removed, parameter-required-added)',
      'ledger-service listed with ?status=breaking',
    ]))
  })

  it('with breaking, fails when the status filter does not list the contract', async () => {
    const api = new FakeApi(seed)
    const list = api.listContracts.bind(api)
    api.listContracts = async (q, status) => (status === 'breaking' ? [] : list(q, status))
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(checks.find((c) => c.name === 'ledger-service listed with ?status=breaking')?.ok).toBe(false)
  })

  it('with breaking, fails when the engine misses the required parameter', async () => {
    const api = new FakeApi(seed)
    const diff = api.contractDiff.bind(api)
    api.contractDiff = async (id, v) => {
      const d = await diff(id, v)
      return { ...d, compatibility: { ...d.compatibility, changes: d.compatibility.changes.filter((c) => c.rule !== 'parameter-required-added') } }
    }
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(checks.find((c) => c.name === 'ledger-service v3 is breaking (path-removed, parameter-required-added)')?.ok).toBe(false)
  })

  it('fails when a declared caller is missing from the contract consumers', async () => {
    const api = new FakeApi(seed)
    const consumers = api.contractConsumers.bind(api)
    api.contractConsumers = async (id) => (await consumers(id)).slice(1)
    const checks = await verify(api, seed, { ...opts, consumersTimeoutMs: 0 })
    const check = checks.find((c) => c.name === 'ledger-service contract lists 12 consumers')
    expect(check?.ok).toBe(false)
    expect(check?.detail).toMatch(/^missing /)
  })

  it('fails when the list has no service name for the contract', async () => {
    const api = new FakeApi(seed)
    const list = api.listContracts.bind(api)
    api.listContracts = async () => (await list()).map((c) => ({ ...c, serviceName: null }))
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service contract listed with its service name')?.ok).toBe(false)
  })

  it('fails when the v2 diff does not add the history path', async () => {
    const api = new FakeApi(seed)
    const diff = api.contractDiff.bind(api)
    api.contractDiff = async (id, v) => ({ ...(await diff(id, v)), changes: [] })
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service v1 → v2 diff adds /ledgers/{id}/history')?.ok).toBe(false)
  })

  it('fails when the server stored a duplicate version for an unchanged spec', async () => {
    const api = new FakeApi(seed)
    const publish = api.publishContractVersion.bind(api)
    api.publishContractVersion = async (id, spec) => ({ ...(await publish(id, spec)), created: true })
    const checks = await verify(api, seed, opts)
    expect(checks.find((c) => c.name === 'ledger-service re-publishing latest spec stores nothing')?.ok).toBe(false)
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

describe('verify spec discovery', () => {
  const opts = { skipObserved: true, sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50, specDiscoveryTimeoutMs: 0 }
  const name = `${seed.services.length} services have a contract from spec discovery`

  it('prints SKIP when the tenant has no SCM connection', async () => {
    const checks = await verify(new FakeApi(seed), seed, opts)
    expect(checks.find((c) => c.name.startsWith('spec discovery SKIP'))?.ok).toBe(true)
    expect(checks.find((c) => c.name === name)).toBeUndefined()
  })

  it('passes when discovery gave each seed service a contract', async () => {
    const api = new FakeApi(seed)
    api.discoverSpecs(seed.services.filter((s) => s.contractVersions === undefined).map((s) => s.name))
    const checks = await verify(api, seed, { ...opts, scmConnected: true })
    expect(checks.find((c) => c.name === name)).toMatchObject({ ok: true })
  })

  it('fails and names the services that discovery missed', async () => {
    const api = new FakeApi(seed)
    const discovered = seed.services.filter((s) => s.contractVersions === undefined).map((s) => s.name)
    api.discoverSpecs(discovered.slice(1))
    const checks = await verify(api, seed, { ...opts, scmConnected: true })
    const check = checks.find((c) => c.name === name)
    expect(check?.ok).toBe(false)
    expect(check?.detail).toContain(`${discovered[0]}: no contract`)
  })
})

describe('verify CI check', () => {
  const opts = { skipObserved: true, sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50 }
  const ciChecks = (checks: Array<{ name: string; ok: boolean }>) =>
    checks.filter((c) => c.name.startsWith('CI check')).map((c) => [c.name, c.ok])

  it('breaking verify runs the CI check and revokes the key', async () => {
    const api = new FakeApi(seed)
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(ciChecks(checks)).toEqual([
      ['CI check of ledger-service is breaking (path-removed /ledgers/{id}/history)', true],
      ['CI check of ledger-service lists 12 affected consumers', true],
      ['CI check key revoked', true],
    ])
    expect(api.checks).toBe(1)
    expect([...api.apiKeys.values()].map((k) => [k.scopes, k.status])).toEqual([[['ci:check'], 'revoked']])
  })

  it('default verify also gets a breaking result against the v2 baseline', async () => {
    const checks = await verify(new FakeApi(seed), seed, opts)
    expect(ciChecks(checks).every(([, ok]) => ok)).toBe(true)
  })

  it('revokes the key when the check fails', async () => {
    const api = new FakeApi(seed)
    api.checkContract = async () => { throw new Error('boom') }
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(checks.find((c) => c.name.startsWith('CI check of ledger-service is breaking'))?.ok).toBe(false)
    expect(checks.find((c) => c.name === 'CI check key revoked')?.ok).toBe(true)
    expect([...api.apiKeys.values()][0].status).toBe('revoked')
  })

  it('the verify key expires within 1 hour', async () => {
    const api = new FakeApi(seed)
    const before = Date.now()
    await verify(api, seed, { ...opts, breaking: true })
    const expiresAt = [...api.apiKeys.values()][0].expiresAt
    expect(expiresAt).toBeTruthy()
    const ms = Date.parse(expiresAt!) - before
    expect(ms).toBeGreaterThan(0)
    expect(ms).toBeLessThanOrEqual(60 * 60 * 1000 + 5000)
  })

  it('a failed key create is one failed check', async () => {
    const api = new FakeApi(seed)
    api.createApiKey = async () => { throw new Error('PLAN_LIMIT_EXCEEDED') }
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(ciChecks(checks)).toEqual([['CI check key created', false]])
    expect(checks.find((c) => c.name === 'CI check key created')?.detail).toContain('PLAN_LIMIT_EXCEEDED')
    expect(api.checks).toBe(0)
  })

  it('fails when the check lists no consumers', async () => {
    const api = new FakeApi(seed)
    const check = api.checkContract.bind(api)
    api.checkContract = async (k, s, spec) => ({ ...(await check(k, s, spec)), affectedConsumers: [] })
    const checks = await verify(api, seed, { ...opts, breaking: true })
    expect(checks.find((c) => c.name === 'CI check of ledger-service lists 12 affected consumers')?.ok).toBe(false)
  })
})
