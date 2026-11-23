import {
  ApiError, type CartograApi, type CatalogService, type Compatibility, type CompatibilityChange, type Contract, type ContractConsumer, type ContractVersion, type Drift, type Graph, type PublishedVersion, type Risk,
  type Team, type UnresolvedSpanService, type VersionDiff,
} from '../src/api.ts'
import { declaredCycles, type Protocol, type SeedData } from '../src/schema.ts'

export class FakeApi implements CartograApi {
  readonly teams: Team[] = []
  readonly services: CatalogService[]
  readonly edges = new Set<string>()
  /** `source>target PROTOCOL` keys by service id, returned by graph('OBSERVED'). */
  readonly observed = new Set<string>()
  readonly calls: string[] = []
  driftRecords: Drift[] = []
  unresolved: UnresolvedSpanService[] = []
  nodeLagReads = 0
  /** Versions per contract id; the spec text itself stands in for the canonical hash. */
  readonly contracts = new Map<string, { serviceId: string; versions: ContractVersion[] }>()
  private seq = 0
  private readonly seed: SeedData

  constructor(seed: SeedData, discovered: string[] = seed.services.map((s) => s.name)) {
    this.seed = seed
    this.services = discovered.map((name) => ({
      id: `svc-${name}`,
      name,
      description: null,
      teamId: null,
      repositoryUrl: null,
      techStack: null,
      metadata: null,
      healthStatus: 'HEALTHY',
      healthEndpoint: null,
      tier: null,
      tags: null,
      slaTarget: null,
      documentationUrl: null,
      runbookUrl: null,
    }))
  }

  discover(name: string) {
    this.services.push({ ...this.services[0], id: `svc-${name}`, name, teamId: null, tier: null })
  }

  async tenantId() {
    return 'tenant'
  }
  async listServices() {
    return this.services.map((s) => ({ ...s }))
  }
  async listTeams() {
    return [...this.teams]
  }
  async createTeam(name: string) {
    this.calls.push(`createTeam:${name}`)
    const team = { id: `team-${++this.seq}`, name }
    this.teams.push(team)
    return team
  }
  async updateService(service: CatalogService, tier: string) {
    this.calls.push(`updateService:${service.name}`)
    this.services.find((s) => s.id === service.id)!.tier = tier
  }
  async assignOwner(serviceId: string, teamId: string) {
    this.calls.push(`assignOwner:${serviceId}`)
    this.services.find((s) => s.id === serviceId)!.teamId = teamId
  }
  async graph(type?: 'DECLARED' | 'OBSERVED'): Promise<Graph> {
    const nodes = this.services.map((s) => ({ serviceId: s.id, name: s.name, teamId: s.teamId, tier: s.tier }))
    const toEdge = (dependencyType: string) => (e: string) => {
      const [pair, protocol] = e.split(' ')
      const [source, target] = pair.split('>')
      return { source, target, dependencyType, protocol }
    }
    const edges = type === 'OBSERVED' ? [...this.observed].map(toEdge('observed')) : [...this.edges].map(toEdge('declared'))
    return { nodes, edges, truncated: false }
  }
  async declareDependency(source: string, target: string, protocol: Protocol) {
    this.calls.push(`declare:${source}>${target} ${protocol}`)
    if (!this.services.some((s) => s.id === source) || !this.services.some((s) => s.id === target)) {
      throw new ApiError(404, 'NOT_FOUND', 'unknown node', 't')
    }
    const key = `${source}>${target} ${protocol}`
    if (this.edges.has(key)) throw new ApiError(409, 'CONFLICT', 'duplicate', 't')
    this.edges.add(key)
  }
  async cycles() {
    return declaredCycles(this.seed)
  }
  async spofs() {
    return ['ledger-service']
  }
  async drifts(): Promise<Drift[]> {
    return this.driftRecords
  }
  async risks(): Promise<Risk[]> {
    const orphans = this.services.filter((s) => s.teamId === null).map((s) => s.id)
    return [{ type: 'orphan', affectedServices: orphans }]
  }
  async unresolvedSpanServices() {
    return [...this.unresolved]
  }
  async createContract(serviceId: string, spec: string): Promise<Contract> {
    this.calls.push(`createContract:${serviceId}`)
    const existing = [...this.contracts].find(([, c]) => c.serviceId === serviceId)
    if (existing) throw new ApiError(409, 'CONFLICT', 'exists', 't', { contractId: existing[0] })
    const id = `contract-${++this.seq}`
    this.contracts.set(id, { serviceId, versions: [fakeVersion(1, spec, false)] })
    return this.getContract(id)
  }
  async publishContractVersion(contractId: string, spec: string): Promise<PublishedVersion> {
    this.calls.push(`publishContractVersion:${contractId}`)
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    const latest = contract.versions.at(-1)!
    if (latest.specHash === spec) return { version: latest, created: false }
    const version = fakeVersion(latest.versionNumber + 1, spec, fakeCompatibility(latest.specHash, spec).isBreaking)
    contract.versions.push(version)
    return { version, created: true }
  }
  async getContract(contractId: string): Promise<Contract> {
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    const serviceName = this.services.find((s) => s.id === contract.serviceId)?.name ?? null
    return { id: contractId, serviceId: contract.serviceId, serviceName, latestVersion: contract.versions.at(-1)! }
  }
  async listContracts(q?: string, status?: 'breaking' | 'compatible'): Promise<Contract[]> {
    const all = await Promise.all([...this.contracts.keys()].map((id) => this.getContract(id)))
    return all.filter((c) => (!q || c.serviceName?.toLowerCase().includes(q.toLowerCase()))
      && (!status || c.latestVersion.breaking === (status === 'breaking')))
  }
  async listContractVersions(contractId: string): Promise<ContractVersion[]> {
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    return [...contract.versions].reverse()
  }
  /** What spec discovery does after a sync: a contract with a discovery version for each repo spec. */
  discoverSpecs(serviceNames: string[]): void {
    for (const name of serviceNames) {
      const serviceId = `svc-${name}`
      const version: ContractVersion = { ...fakeVersion(1, `discovered ${name}`, false), source: 'discovery' }
      const existing = [...this.contracts.values()].find((c) => c.serviceId === serviceId)
      if (existing) {
        existing.versions.push({ ...version, id: `v-d-${name}`, versionNumber: existing.versions.length + 1 })
      } else {
        this.contracts.set(`contract-${++this.seq}`, { serviceId, versions: [version] })
      }
    }
  }
  /** The sources of declared HTTP edges into the contract's service, as contract would project them. */
  async contractConsumers(contractId: string): Promise<ContractConsumer[]> {
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    const sources = new Set([...this.edges]
      .map((e) => e.split(' '))
      .filter(([pair, protocol]) => protocol === 'HTTP' && pair.split('>')[1] === contract.serviceId)
      .map(([pair]) => pair.split('>')[0]))
    return [...sources].map((id) => ({
      serviceId: id,
      serviceName: this.services.find((s) => s.id === id)?.name ?? null,
      dependencyTypes: ['declared'],
      protocols: ['http'],
    }))
  }
  /** Reports each path key the version adds over the previous one; the spec text is in `specHash`. */
  async contractDiff(contractId: string, versionNumber: number): Promise<VersionDiff> {
    const contract = this.contracts.get(contractId)
    const to = contract?.versions[versionNumber - 1]
    if (!contract || !to) throw new ApiError(404, 'NOT_FOUND', 'unknown version', 't')
    const from = contract.versions[versionNumber - 2] ?? null
    const before = new Set(from ? specPaths(from.specHash) : [])
    const added = specPaths(to.specHash).filter((p) => !before.has(p))
    return {
      from,
      to,
      changes: added.map((p) => ({ op: 'added', pointer: `/paths/${p.replaceAll('~', '~0').replaceAll('/', '~1')}`, path: ['paths', p] })),
      lines: added.map((p) => ({ kind: 'added', left: null, right: `  ${p}:` })),
      compatibility: fakeCompatibility(from?.specHash ?? null, to.specHash),
    }
  }
}

function specPaths(spec: string): string[] {
  return [...spec.matchAll(/^ {2}"([^"]+)":$/gm)].map((m) => m[1])
}

/** Paths whose block declares a `currency` query parameter. */
function currencyPaths(spec: string): string[] {
  return spec.split(/^(?= {2}")/m).filter((block) => block.includes('- name: currency')).map((block) => /^ {2}"([^"]+)":/.exec(block)![1])
}

/** The engine's rules the seed specs can hit: paths added or removed, and a new required `currency` parameter. */
function fakeCompatibility(fromSpec: string | null, toSpec: string): Compatibility {
  const before = fromSpec ? specPaths(fromSpec) : []
  const after = specPaths(toSpec)
  const hadCurrency = new Set(fromSpec ? currencyPaths(fromSpec) : [])
  const hit = (rule: string, breaking: boolean, path: string, method: string | null = null, location: string | null = null): CompatibilityChange => ({
    rule, breaking, direction: method ? 'request' : 'operation', method, path, location, message: rule,
  })
  const changes = [
    ...before.filter((p) => !after.includes(p)).map((p) => hit('path-removed', true, p)),
    ...after.filter((p) => !before.includes(p)).map((p) => hit('path-added', false, p)),
    ...currencyPaths(toSpec).filter((p) => before.includes(p) && !hadCurrency.has(p))
      .map((p) => hit('parameter-required-added', true, p, 'GET', 'query.currency')),
  ]
  return { isBreaking: changes.some((c) => c.breaking), changes }
}

function fakeVersion(versionNumber: number, spec: string, breaking: boolean | null): ContractVersion {
  return {
    id: `v-${versionNumber}`, versionNumber, label: /version: "([^"]+)"/.exec(spec)?.[1] ?? null, specHash: spec,
    breaking, source: 'manual',
  }
}
