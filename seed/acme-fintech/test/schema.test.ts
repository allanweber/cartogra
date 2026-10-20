import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  allCycles, declaredCalls, declaredCycles, dependentsOf, dormantCalls, findElementaryCycles,
  loadSeedFile, unownedServices, validateSeed, type SeedData,
} from '../src/schema.ts'

const seed = loadSeedFile(resolve(import.meta.dirname, '../seed-data.json'))

describe('seed-data.json', () => {
  it('has 20 services and 5 teams', () => {
    expect(seed.services).toHaveLength(20)
    expect(seed.teams).toHaveLength(5)
  })

  it('has 3 orphans', () => {
    expect(unownedServices(seed)).toHaveLength(3)
  })

  it('declares exactly 2 elementary cycles', () => {
    expect(declaredCycles(seed)).toHaveLength(2)
  })

  it('has one god service with 12 dependents', () => {
    expect(dependentsOf(seed, 'ledger-service')).toHaveLength(12)
    const fanIn = new Map<string, number>()
    for (const c of declaredCalls(seed)) fanIn.set(c.to, (fanIn.get(c.to) ?? 0) + 1)
    expect([...fanIn.values()].filter((n) => n >= 12)).toHaveLength(1)
  })

  it('has one dormant undeclared call that adds no cycle', () => {
    expect(dormantCalls(seed)).toHaveLength(1)
    expect(allCycles(seed)).toHaveLength(2)
  })

  it('only references real services', () => {
    expect(() => validateSeed(seed)).not.toThrow()
  })
})

describe('validateSeed', () => {
  const base: SeedData = {
    name: 'x', namespace: 'x', teams: ['A'],
    services: [{ name: 'a', team: 'A', tier: 'STANDARD', description: '', repo: { provider: 'github', stack: 'java', port: 1 } }, { name: 'b', team: null, tier: 'STANDARD', description: '', repo: { provider: 'azure', stack: 'node', port: 2 } }],
    calls: [{ from: 'a', to: 'b', declared: true, enabled: true }],
  }

  it('rejects unknown call targets, duplicates and self calls', () => {
    expect(() => validateSeed({ ...base, calls: [{ from: 'a', to: 'zzz', declared: true, enabled: true }] })).toThrow(/unknown target/)
    expect(() => validateSeed({ ...base, calls: [base.calls[0], base.calls[0]] })).toThrow(/duplicate/)
    expect(() => validateSeed({ ...base, calls: [{ from: 'a', to: 'a', declared: true, enabled: true }] })).toThrow(/self call/)
  })

  it('rejects duplicate service names case-insensitively', () => {
    expect(() => validateSeed({ ...base, services: [...base.services, { ...base.services[0], name: 'A', repo: { provider: 'github', stack: 'java', port: 3 } }] })).toThrow(/unique/)
  })
})

describe('findElementaryCycles', () => {
  it('counts a directed cycle once regardless of start', () => {
    expect(findElementaryCycles([['a', 'b'], ['b', 'c'], ['c', 'a']])).toHaveLength(1)
  })

  it('treats opposite directions as different cycles', () => {
    expect(findElementaryCycles([['a', 'b'], ['b', 'c'], ['c', 'a'], ['a', 'c'], ['c', 'b'], ['b', 'a']]).length).toBeGreaterThan(1)
  })

  it('finds none in a DAG', () => {
    expect(findElementaryCycles([['a', 'b'], ['b', 'c']])).toHaveLength(0)
  })
})
