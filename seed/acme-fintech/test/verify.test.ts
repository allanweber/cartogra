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
