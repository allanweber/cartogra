import type { CartograApi } from './api.ts'
import { loadSeed, type LoadOptions, type LoadSummary } from './load.ts'
import { breakingLabel, breakingParameterPath, breakingRemovedPath, breakingVersion, historyPath, openapiSpec } from './repos.ts'
import {
  allCycles, callKey, callKind, callProtocol, declaredCalls, declaredCycles, dormantCalls, edgeKey, externalCalls, silentDeclaredCalls,
  unownedServices, type SeedData,
} from './schema.ts'

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
  /** The tenant has an SCM connection, so each sync runs spec discovery (story 5.8). Unset: the check is skipped. */
  scmConnected?: boolean
  specDiscoveryTimeoutMs?: number
  consumersTimeoutMs?: number
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
    declaredEdges: graph.edges.map((e) => edgeKey(nodeName.get(e.source)!, nodeName.get(e.target)!, e.protocol)).sort(),
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
  const secondChanges = second.teamsCreated + second.tiersUpdated + second.ownersAssigned + second.edgesCreated
    + second.contractsCreated + second.contractVersionsPublished
  add('second load changes nothing', secondChanges === 0, JSON.stringify(second))
  add('state identical after second load', JSON.stringify(first) === JSON.stringify(after))
  const presentTeams = new Set(after.teams.map((t) => t.toLowerCase()))
  add(`${seed.teams.length} teams present`, seed.teams.every((t) => presentTeams.has(t.toLowerCase())))
  const absentDeclared = missingDeclared(seed, after)
  add('all declared edges present', absentDeclared.length === 0, absentDeclared.join(', '))

  await verifyContracts(api, seed, second, add, options.breaking ?? false)
  await verifySpecDiscovery(api, seed, options, sleep, add)
  await verifyConsumers(api, seed, second, add, options.consumersTimeoutMs ?? 60_000, sleep)

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

  const edgeKeys = async (type: 'DECLARED' | 'OBSERVED') =>
    new Set((await api.graph(type)).edges.map((e) => edgeKey(nodeName.get(e.source)!, nodeName.get(e.target)!, e.protocol)))
  const observedEdges = () => edgeKeys('OBSERVED')

  if (!options.skipObserved) {
    const silent = new Set(silentDeclaredCalls(seed).map(callKey))
    const expected = declaredCalls(seed).map(callKey).filter((key) => !silent.has(key))
    const missing = await pollMissing(expected, observedEdges, options.observedTimeoutMs ?? 90_000, sleep)
    add('declared calls observed from spans', missing.length === 0, missing.join(', '))

    const observedNow = await observedEdges()
    for (const kind of ['messaging', 'db'] as const) {
      const keys = declaredCalls(seed).filter((c) => callKind(c) === kind && c.enabled).map(callKey)
      const absent = keys.filter((key) => !observedNow.has(key))
      add(`${keys.length} ${kind === 'messaging' ? 'kafka' : 'db'} edges observed`, keys.length > 0 && absent.length === 0, absent.join(', '))
    }

    const peers = externalCalls(seed).map((c) => c.to.toLowerCase())
    const unlisted = await pollMissing(peers, unresolvedNames(api), options.observedTimeoutMs ?? 90_000, sleep)
    add(`${peers.length} external peers listed as unresolved`, unlisted.length === 0, unlisted.join(', '))
  }

  if (options.withDrift) {
    const dormant = dormantCalls(seed).map((c) => `${c.from}>${c.to}`)
    const dormantEdges = dormantCalls(seed).map(callKey)
    const silent = silentDeclaredCalls(seed).map((c) => `${c.from}>${c.to}`)
    await options.withDrift.enable()
    try {
      const missing = await pollMissing(dormantEdges, observedEdges, options.observedTimeoutMs ?? 90_000, sleep)
      const declared = await edgeKeys('DECLARED')
      add(`${dormant.length} undeclared edges observed`, missing.length === 0, missing.join(', '))
      add('undeclared edges stay undeclared', dormantEdges.every((key) => !declared.has(key)))
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

async function verifyContracts(
  api: CartograApi,
  seed: SeedData,
  loaded: LoadSummary,
  add: (name: string, ok: boolean, detail?: string) => void,
  breaking: boolean,
): Promise<void> {
  const listed = new Map((await api.listContracts()).map((c) => [c.id, c]))
  const listedBreaking = breaking ? new Set((await api.listContracts(undefined, 'breaking')).map((c) => c.id)) : new Set<string>()
  for (const s of seed.services) {
    const versions = s.contractVersions
    if (versions === undefined) continue
    const id = loaded.contracts[s.name]
    if (!id) {
      add(`${s.name} contract exists`, false)
      continue
    }
    const latest = (await api.getContract(id)).latestVersion
    const atBreaking = breaking || latest.label === breakingLabel(s)
    const expectedVersion = atBreaking ? breakingVersion(s) : versions
    add(`${s.name} contract exists`, true)
    add(`${s.name} contract at v${expectedVersion}`, latest.versionNumber >= expectedVersion && latest.label === `1.${expectedVersion - 1}.0`,
      `v${latest.versionNumber} label ${latest.label}`)
    const row = listed.get(id)
    add(`${s.name} contract listed with its service name`, row?.serviceName === s.name && row.latestVersion.versionNumber === latest.versionNumber,
      row ? `name ${row.serviceName}, v${row.latestVersion.versionNumber}` : 'not in the list')
    if (versions > 1) {
      const diff = await api.contractDiff(id, 2)
      const history = historyPath(s)
      add(`${s.name} v1 → v2 diff adds ${history}`,
        diff.from?.versionNumber === 1
          && diff.changes.some((c) => c.op === 'added' && c.path.length === 2 && c.path[0] === 'paths' && c.path[1] === history)
          && diff.lines.some((l) => l.kind === 'added' && l.right?.includes(history) === true),
        `${diff.changes.length} change(s): ${diff.changes.map((c) => `${c.op} ${c.pointer}`).join(', ')}`)
      const rules = diff.compatibility.changes.map((c) => `${c.rule} ${c.path}`)
      add(`${s.name} v2 is compatible (path-added)`,
        diff.to.breaking !== true && diff.compatibility.isBreaking === false
          && diff.compatibility.changes.some((c) => c.rule === 'path-added' && c.path === history),
        `breaking ${diff.to.breaking}, rules: ${rules.join(', ')}`)
    }
    if (breaking) {
      const diff = await api.contractDiff(id, latest.versionNumber)
      const hits = diff.compatibility.changes
      add(`${s.name} v${latest.versionNumber} is breaking (path-removed, parameter-required-added)`,
        latest.breaking === true && diff.compatibility.isBreaking
          && hits.some((c) => c.rule === 'path-removed' && c.path === breakingRemovedPath(s))
          && hits.some((c) => c.rule === 'parameter-required-added' && c.method === 'GET' && c.path === breakingParameterPath(s)
            && c.location === 'query.currency'),
        `breaking ${latest.breaking}, rules: ${hits.map((c) => `${c.rule} ${c.method ?? ''} ${c.path} ${c.location ?? ''}`).join(', ')}`)
      add(`${s.name} listed with ?status=breaking`, listedBreaking.has(id))
    }
    if (versions > 1) {
      const republished = await api.publishContractVersion(id,
        atBreaking ? openapiSpec(s, breakingVersion(s), { breaking: true }) : openapiSpec(s, versions))
      add(`${s.name} re-publishing latest spec stores nothing`,
        !republished.created && republished.version.versionNumber === latest.versionNumber
          && republished.version.specHash === latest.specHash)
    }
  }
}

/**
 * Spec discovery (5.8): each seed repo has an openapi.yaml, so each seed service gets a contract. A service without
 * `contractVersions` gets its contract only from discovery, so it must have a `discovery` version. A service that the
 * load also publishes keeps its manual versions: its repo spec has the same canonical hash as the latest manual
 * version, and discovery then stores nothing (UNCHANGED).
 */
async function verifySpecDiscovery(
  api: CartograApi,
  seed: SeedData,
  options: VerifyOptions,
  sleep: (ms: number) => Promise<void>,
  add: (name: string, ok: boolean, detail?: string) => void,
): Promise<void> {
  if (!options.scmConnected) {
    add('spec discovery SKIP (no SCM connection)', true, 'SKIP')
    return
  }
  const missing = async (): Promise<string[]> => {
    const contracts = new Map((await api.listContracts()).map((c) => [c.serviceName?.toLowerCase(), c]))
    const absent: string[] = []
    for (const s of seed.services) {
      const contract = contracts.get(s.name.toLowerCase())
      if (!contract) {
        absent.push(`${s.name}: no contract`)
      } else if (s.contractVersions === undefined) {
        const versions = await api.listContractVersions(contract.id)
        if (!versions.some((v) => v.source === 'discovery')) absent.push(`${s.name}: no discovery version`)
      }
    }
    return absent
  }
  const deadline = Date.now() + (options.specDiscoveryTimeoutMs ?? 120_000)
  let absent = await missing()
  while (absent.length > 0 && Date.now() < deadline) {
    await sleep(options.pollMs ?? 3000)
    absent = await missing()
  }
  add(`${seed.services.length} services have a contract from spec discovery`, absent.length === 0, absent.join(', '))
}

/** Each contract lists as consumers the services with a declared http call into it (topology edges, 5.4). */
async function verifyConsumers(
  api: CartograApi,
  seed: SeedData,
  loaded: LoadSummary,
  add: (name: string, ok: boolean, detail?: string) => void,
  timeoutMs: number,
  sleep: (ms: number) => Promise<void>,
): Promise<void> {
  for (const s of seed.services) {
    const id = loaded.contracts[s.name]
    if (s.contractVersions === undefined || !id) continue
    const expected = [...new Set(declaredCalls(seed)
      .filter((c) => c.to === s.name && callProtocol(c) === 'HTTP')
      .map((c) => c.from))]
    const listed = async () => new Set((await api.contractConsumers(id)).map((c) => c.serviceName ?? c.serviceId))
    const missing = await pollMissing(expected, listed, timeoutMs, sleep)
    const extra = [...(await listed())].filter((name) => !expected.includes(name))
    add(`${s.name} contract lists ${expected.length} consumers`, missing.length === 0 && extra.length === 0,
      [...missing.map((n) => `missing ${n}`), ...extra.map((n) => `extra ${n}`)].join(', '))
  }
}

function unresolvedNames(api: CartograApi): () => Promise<Set<string>> {
  return async () => new Set((await api.unresolvedSpanServices()).map((u) => u.name.toLowerCase()))
}

export function godService(seed: SeedData): string {
  const counts = new Map<string, Set<string>>()
  for (const c of declaredCalls(seed)) counts.set(c.to, (counts.get(c.to) ?? new Set()).add(c.from))
  return [...counts].sort((a, b) => b[1].size - a[1].size)[0][0]
}

function missingDeclared(seed: SeedData, snap: Snapshot): string[] {
  const present = new Set(snap.declaredEdges)
  return declaredCalls(seed).map(callKey).filter((key) => !present.has(key))
}

async function waitForDeclared(api: CartograApi, seed: SeedData, options: VerifyOptions, sleep: (ms: number) => Promise<void>) {
  const deadline = Date.now() + (options.syncTimeoutMs ?? 60_000)
  for (;;) {
    const snap = await snapshot(api, seed)
    if (missingDeclared(seed, snap).length === 0 || Date.now() >= deadline) return snap
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
