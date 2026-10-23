import type { CartograApi } from './api.ts'
import { loadSeed, type LoadOptions } from './load.ts'
import { allCycles, declaredCalls, declaredCycles, dormantCalls, externalCalls, silentDeclaredCalls, unownedServices, type SeedData } from './schema.ts'

export interface Snapshot {
  teams: string[]
  services: string[]
  declaredEdges: string[]
}

export interface Check {
  name: string
  ok: boolean
  detail?: string
}

export interface VerifyOptions extends LoadOptions {
  withDrift?: { enable: () => Promise<void>; disable: () => Promise<void> }
  skipObserved?: boolean
  observedTimeoutMs?: number
  driftTimeoutMs?: number
}

export async function snapshot(api: CartograApi, seed: SeedData): Promise<Snapshot> {
  const [teams, services, graph] = await Promise.all([api.listTeams(), api.listServices(), api.graph('DECLARED')])
  const teamName = new Map(teams.map((t) => [t.id, t.name]))
  const nodeName = new Map(graph.nodes.map((n) => [n.serviceId, n.name]))
  const seeded = new Set(seed.services.map((s) => s.name.toLowerCase()))
  return {
    teams: teams.map((t) => t.name).sort(),
    services: services
      .filter((s) => seeded.has(s.name.toLowerCase()))
      .map((s) => `${s.name}|${s.tier}|${s.teamId ? teamName.get(s.teamId) : '-'}`)
      .sort(),
    declaredEdges: graph.edges.map((e) => `${nodeName.get(e.source)}>${nodeName.get(e.target)}`).sort(),
  }
}

export async function verify(api: CartograApi, seed: SeedData, options: VerifyOptions = {}): Promise<Check[]> {
  const checks: Check[] = []
  const add = (name: string, ok: boolean, detail?: string) => checks.push({ name, ok, detail })
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))

  await loadSeed(api, seed, options)
  const first = await waitForDeclared(api, seed, options, sleep)
  const second = await loadSeed(api, seed, options)
  const after = await waitForDeclared(api, seed, options, sleep)
  add('second load changes nothing', second.teamsCreated + second.tiersUpdated + second.ownersAssigned + second.edgesCreated === 0, JSON.stringify(second))
  add('state identical after second load', JSON.stringify(first) === JSON.stringify(after))
  add(`${seed.teams.length} teams present`, seed.teams.every((t) => after.teams.includes(t)))
  add('all declared edges present', after.declaredEdges.length === declaredCalls(seed).length, `${after.declaredEdges.length}/${declaredCalls(seed).length}`)

  const graph = await api.graph()
  const nodeId = new Map(graph.nodes.map((n) => [n.name, n.serviceId]))
  const nodeName = new Map(graph.nodes.map((n) => [n.serviceId, n.name]))

  const cycles = await api.cycles()
  add(`${declaredCycles(seed).length} cycles reported`, cycles.length === declaredCycles(seed).length, `got ${cycles.length}`)

  const god = godService(seed)
  add(`${god} flagged as SPOF`, (await api.spofs()).includes(god))

  const orphanIds = new Set(unownedServices(seed).map((n) => nodeId.get(n)))
  const orphanRisks = new Set((await api.risks()).filter((r) => r.type === 'orphan').flatMap((r) => r.affectedServices))
  add(`${orphanIds.size} orphans in risks`, [...orphanIds].every((id) => id !== undefined && orphanRisks.has(id)))

  const observedPairs = async () =>
    new Set((await api.graph('OBSERVED')).edges.map((e) => `${nodeName.get(e.source)}>${nodeName.get(e.target)}`))

  if (!options.skipObserved) {
    const silent = new Set(silentDeclaredCalls(seed).map((c) => `${c.from}>${c.to}`))
    const expected = declaredCalls(seed).map((c) => `${c.from}>${c.to}`).filter((key) => !silent.has(key))
    const missing = await pollMissing(expected, observedPairs, options.observedTimeoutMs ?? 90_000, sleep)
    add('declared calls observed from spans', missing.length === 0, missing.join(', '))

    const peers = externalCalls(seed).map((c) => c.to.toLowerCase())
    const unlisted = await pollMissing(peers, unresolvedNames(api), options.observedTimeoutMs ?? 90_000, sleep)
    add(`${peers.length} external peers listed as unresolved`, unlisted.length === 0, unlisted.join(', '))
  }

  if (options.withDrift) {
    const dormant = dormantCalls(seed).map((c) => `${c.from}>${c.to}`)
    const silent = silentDeclaredCalls(seed).map((c) => `${c.from}>${c.to}`)
    await options.withDrift.enable()
    try {
      const missing = await pollMissing(dormant, observedPairs, options.observedTimeoutMs ?? 90_000, sleep)
      const declared = new Set((await api.graph('DECLARED')).edges.map((e) => `${nodeName.get(e.source)}>${nodeName.get(e.target)}`))
      add(`${dormant.length} undeclared edges observed`, missing.length === 0, missing.join(', '))
      add('undeclared edges stay undeclared', dormant.every((key) => !declared.has(key)))
      add('cycle count unchanged with drift on', (await api.cycles()).length === allCycles(seed).length)

      const wanted = [...dormant.map((key) => `UNDECLARED ${key}`), ...silent.map((key) => `MISSING ${key}`)]
      const absent = await pollDrifts(api, nodeName, wanted, options.driftTimeoutMs ?? 300_000, sleep)
      add(`${wanted.length} drift records raised`, absent.length === 0, absent.join(', '))
    } finally {
      await options.withDrift.disable()
    }
  }
  return checks
}

function unresolvedNames(api: CartograApi): () => Promise<Set<string>> {
  return async () => new Set((await api.unresolvedSpanServices()).map((u) => u.name.toLowerCase()))
}

export function godService(seed: SeedData): string {
  const counts = new Map<string, Set<string>>()
  for (const c of declaredCalls(seed)) counts.set(c.to, (counts.get(c.to) ?? new Set()).add(c.from))
  return [...counts].sort((a, b) => b[1].size - a[1].size)[0][0]
}

async function waitForDeclared(api: CartograApi, seed: SeedData, options: VerifyOptions, sleep: (ms: number) => Promise<void>) {
  const deadline = Date.now() + (options.syncTimeoutMs ?? 60_000)
  for (;;) {
    const snap = await snapshot(api, seed)
    if (snap.declaredEdges.length >= declaredCalls(seed).length || Date.now() >= deadline) return snap
    await sleep(options.pollMs ?? 3000)
  }
}

async function pollMissing(
  expected: string[],
  observed: () => Promise<Set<string>>,
  timeoutMs: number,
  sleep: (ms: number) => Promise<void>,
): Promise<string[]> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const have = await observed()
    const missing = expected.filter((e) => !have.has(e))
    if (missing.length === 0 || Date.now() >= deadline) return missing
    await sleep(3000)
  }
}

async function pollDrifts(
  api: CartograApi,
  nodeName: Map<string, string>,
  expected: string[],
  timeoutMs: number,
  sleep: (ms: number) => Promise<void>,
): Promise<string[]> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const have = new Set(
      (await api.drifts()).map((d) => `${d.type} ${nodeName.get(d.sourceServiceId)}>${nodeName.get(d.targetServiceId)}`),
    )
    const absent = expected.filter((e) => !have.has(e))
    if (absent.length === 0 || Date.now() >= deadline) return absent
    await sleep(3000)
  }
}
