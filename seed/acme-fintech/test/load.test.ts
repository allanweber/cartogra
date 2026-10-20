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
    expect(summary).toMatchObject({ teamsCreated: 5, tiersUpdated: 20, ownersAssigned: 17, edgesCreated: declaredCalls(seed).length })
    expect(api.services.filter((s) => s.teamId === null)).toHaveLength(3)
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
    expect(api.services).toHaveLength(18)
  })

  it('never calls anything that creates a service', async () => {
    const api = new FakeApi(seed)
    await loadSeed(api, seed, fast)
    expect(Object.getOwnPropertyNames(Object.getPrototypeOf(api)).some((m) => /createService/i.test(m))).toBe(false)
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
