import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { loadSeed, MissingServicesError } from '../src/load.ts'
import { declaredCalls, loadSeedFile } from '../src/schema.ts'
import { FakeApi } from './fakeApi.ts'

const seed = loadSeedFile(resolve(import.meta.dirname, '../seed-data.json'))
const fast = { sleep: async () => {}, pollMs: 0, discoveryTimeoutMs: 50, syncTimeoutMs: 50 }

describe('loadSeed', () => {
  it('creates teams, sets tiers/owners and declares all edges', async () => {
    const api = new FakeApi(seed)
    const summary = await loadSeed(api, seed, fast)
    expect(summary).toMatchObject({ teamsCreated: 5, tiersUpdated: 21, ownersAssigned: 18, edgesCreated: declaredCalls(seed).length })
    expect(api.services.filter((s) => s.teamId === null)).toHaveLength(3)
  })

  it('assigns no owners with skipOwners, leaving ownership to CODEOWNERS', async () => {
    const api = new FakeApi(seed)
    const summary = await loadSeed(api, seed, { ...fast, skipOwners: true })
    expect(summary).toMatchObject({ teamsCreated: 5, tiersUpdated: 21, ownersAssigned: 0 })
    expect(api.services.filter((s) => s.teamId === null)).toHaveLength(21)
  })

  it('leaves identical state when run twice', async () => {
    const api = new FakeApi(seed)
    await loadSeed(api, seed, fast)
    const before = JSON.stringify([api.teams, api.services, [...api.edges].sort()])
    const second = await loadSeed(api, seed, fast)
    expect(JSON.stringify([api.teams, api.services, [...api.edges].sort()])).toBe(before)
    expect(second).toMatchObject({ teamsCreated: 0, tiersUpdated: 0, ownersAssigned: 0, edgesCreated: 0 })
  })

  it('fails listing services discovery has not produced, without creating any', async () => {
    const api = new FakeApi(seed, seed.services.slice(2).map((s) => s.name))
    const error = await loadSeed(api, seed, fast).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(MissingServicesError)
    expect((error as MissingServicesError).missing).toEqual([seed.services[0].name, seed.services[1].name])
    expect(api.calls).toHaveLength(0)
    expect(api.services).toHaveLength(19)
  })

  it('never calls anything that creates a service', async () => {
    const api = new FakeApi(seed)
    await loadSeed(api, seed, fast)
    expect(Object.getOwnPropertyNames(Object.getPrototypeOf(api)).some((m) => /createService/i.test(m))).toBe(false)
  })

  it('declares each call with its protocol, including a kafka edge beside an http one on the same pair', async () => {
    const api = new FakeApi(seed)
    await loadSeed(api, seed, fast)
    expect(api.edges).toContain('svc-payments-service>svc-notification-service HTTP')
    expect(api.edges).toContain('svc-payments-service>svc-notification-service KAFKA')
    expect(api.edges).toContain('svc-ledger-service>svc-ledger-db DB')
  })

  it('creates the ledger-service contract with v1 then publishes v2', async () => {
    const api = new FakeApi(seed)
    const summary = await loadSeed(api, seed, fast)
    expect(summary).toMatchObject({ contractsCreated: 1, contractVersionsPublished: 1 })
    const contract = await api.getContract(summary.contracts['ledger-service'])
    expect(contract.serviceId).toBe('svc-ledger-service')
    expect(contract.latestVersion).toMatchObject({ versionNumber: 2, label: '1.1.0' })
  })

  it('publishes the breaking version after v2 with breaking, once', async () => {
    const api = new FakeApi(seed)
    const first = await loadSeed(api, seed, { ...fast, breaking: true })
    expect(first).toMatchObject({ contractsCreated: 1, contractVersionsPublished: 2 })
    const latest = (await api.getContract(first.contracts['ledger-service'])).latestVersion
    expect(latest).toMatchObject({ versionNumber: 3, label: '1.2.0', breaking: true })

    const second = await loadSeed(api, seed, { ...fast, breaking: true })
    expect(second).toMatchObject({ contractsCreated: 0, contractVersionsPublished: 0 })
    expect((await api.getContract(first.contracts['ledger-service'])).latestVersion.versionNumber).toBe(3)
  })

  it('keeps the breaking version on a plain load after load --breaking, and says so', async () => {
    const api = new FakeApi(seed)
    const first = await loadSeed(api, seed, { ...fast, breaking: true })
    const lines: string[] = []
    const plain = await loadSeed(api, seed, { ...fast, log: (m) => lines.push(m) })
    expect(plain).toMatchObject({ contractsCreated: 0, contractVersionsPublished: 0 })
    expect((await api.getContract(first.contracts['ledger-service'])).latestVersion).toMatchObject({ versionNumber: 3, label: '1.2.0' })
    expect(lines.filter((l) => /breaking version/.test(l))).toEqual([
      'contract kept: ledger-service is at the breaking version v3 (1.2.0); a plain load does not publish over it',
    ])
  })

  it('reuses the existing contract on a re-run and stores no new version', async () => {
    const api = new FakeApi(seed)
    const first = await loadSeed(api, seed, fast)
    const second = await loadSeed(api, seed, fast)
    expect(second).toMatchObject({ contractsCreated: 0, contractVersionsPublished: 0 })
    expect(second.contracts).toEqual(first.contracts)
    expect((await api.getContract(first.contracts['ledger-service'])).latestVersion.versionNumber).toBe(2)
  })

  it('skips edges that already exist on the server (409)', async () => {
    const api = new FakeApi(seed)
    await loadSeed(api, seed, fast)
    const graph = api.graph.bind(api)
    api.graph = async (type) => (type === 'DECLARED' ? { nodes: [], edges: [], truncated: false } : graph(type))
    const second = await loadSeed(api, seed, fast)
    expect(second.edgesCreated).toBe(0)
  })
})
