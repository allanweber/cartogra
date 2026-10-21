import { ApiError, type CartograApi, type CatalogService } from './api.ts'
import { declaredCalls, type SeedData } from './schema.ts'

export interface LoadOptions {
  log?: (message: string) => void
  sleep?: (ms: number) => Promise<void>
  discoveryTimeoutMs?: number
  syncTimeoutMs?: number
  pollMs?: number
  /** Leave owners unassigned so CODEOWNERS (SCM sync) is the only ownership source. */
  skipOwners?: boolean
}

export interface LoadSummary {
  teamsCreated: number
  tiersUpdated: number
  ownersAssigned: number
  edgesCreated: number
  edgesExisting: number
}

export class MissingServicesError extends Error {
  readonly missing: string[]

  constructor(missing: string[]) {
    super(
      `Discovery has not produced ${missing.length} service(s): ${missing.join(', ')}. ` +
        'Check the cluster is registered in ingestion and the namespace carries the tenant label.',
    )
    this.missing = missing
  }
}

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

export async function loadSeed(api: CartograApi, seed: SeedData, options: LoadOptions = {}): Promise<LoadSummary> {
  const log = options.log ?? (() => {})
  const sleep = options.sleep ?? defaultSleep
  const pollMs = options.pollMs ?? 3000
  const discoveryTimeoutMs = options.discoveryTimeoutMs ?? 120_000
  const syncTimeoutMs = options.syncTimeoutMs ?? 60_000
  const summary: LoadSummary = { teamsCreated: 0, tiersUpdated: 0, ownersAssigned: 0, edgesCreated: 0, edgesExisting: 0 }

  const services = await waitForServices(api, seed, discoveryTimeoutMs, pollMs, sleep, log)

  const teamIds = new Map<string, string>()
  for (const team of await api.listTeams()) teamIds.set(team.name.toLowerCase(), team.id)
  for (const name of seed.teams) {
    if (teamIds.has(name.toLowerCase())) continue
    const created = await api.createTeam(name)
    teamIds.set(name.toLowerCase(), created.id)
    summary.teamsCreated++
    log(`team created: ${name}`)
  }

  for (const seeded of seed.services) {
    const current = services.get(seeded.name.toLowerCase())!
    if (current.tier !== seeded.tier) {
      await api.updateService(current, seeded.tier)
      summary.tiersUpdated++
      log(`tier set: ${seeded.name} -> ${seeded.tier}`)
    }
    if (seeded.team !== null && !options.skipOwners) {
      const teamId = teamIds.get(seeded.team.toLowerCase())!
      if (current.teamId !== teamId) {
        await api.assignOwner(current.id, teamId)
        summary.ownersAssigned++
        log(`owner set: ${seeded.name} -> ${seeded.team}`)
      }
    }
  }

  const nodeIds = await waitForNodes(api, seed, options.skipOwners ? new Map() : teamIds, syncTimeoutMs, pollMs, sleep)
  const existing = new Set((await api.graph('DECLARED')).edges.map((e) => `${e.source}>${e.target}`))

  for (const call of declaredCalls(seed)) {
    const source = nodeIds.get(call.from)!
    const target = nodeIds.get(call.to)!
    if (existing.has(`${source}>${target}`)) {
      summary.edgesExisting++
      continue
    }
    if (await declareWithRetry(api, source, target, pollMs, sleep)) summary.edgesCreated++
    else summary.edgesExisting++
  }
  log(`edges: ${summary.edgesCreated} created, ${summary.edgesExisting} already declared`)
  return summary
}

async function waitForServices(
  api: CartograApi,
  seed: SeedData,
  timeoutMs: number,
  pollMs: number,
  sleep: (ms: number) => Promise<void>,
  log: (message: string) => void,
): Promise<Map<string, CatalogService>> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const byName = new Map((await api.listServices()).map((s) => [s.name.toLowerCase(), s]))
    const missing = seed.services.map((s) => s.name).filter((n) => !byName.has(n.toLowerCase()))
    if (missing.length === 0) return byName
    if (Date.now() >= deadline) throw new MissingServicesError(missing)
    log(`waiting for discovery: ${missing.length} service(s) missing`)
    await sleep(pollMs)
  }
}

async function waitForNodes(
  api: CartograApi,
  seed: SeedData,
  teamIds: Map<string, string>,
  timeoutMs: number,
  pollMs: number,
  sleep: (ms: number) => Promise<void>,
): Promise<Map<string, string>> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const nodes = new Map((await api.graph()).nodes.map((n) => [n.name.toLowerCase(), n]))
    const stale = seed.services.filter((s) => {
      const node = nodes.get(s.name.toLowerCase())
      const expectedTeam = s.team === null ? undefined : teamIds.get(s.team.toLowerCase())
      return !node || node.tier !== s.tier || (expectedTeam !== undefined && node.teamId !== expectedTeam)
    })
    if (stale.length === 0) return new Map(seed.services.map((s) => [s.name, nodes.get(s.name.toLowerCase())!.serviceId]))
    if (Date.now() >= deadline) {
      throw new Error(`Topology graph_nodes not in sync after ${timeoutMs} ms: ${stale.map((s) => s.name).join(', ')}`)
    }
    await sleep(pollMs)
  }
}

async function declareWithRetry(
  api: CartograApi,
  source: string,
  target: string,
  pollMs: number,
  sleep: (ms: number) => Promise<void>,
): Promise<boolean> {
  for (let attempt = 1; ; attempt++) {
    try {
      await api.declareDependency(source, target)
      return true
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) return false
      if (error instanceof ApiError && error.status === 404 && attempt < 10) {
        await sleep(pollMs)
        continue
      }
      throw error
    }
  }
}
