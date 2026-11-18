import {
  ApiError, type CartograApi, type CatalogService, type Contract, type ContractVersion, type Drift, type Graph, type PublishedVersion, type Risk,
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
    this.contracts.set(id, { serviceId, versions: [fakeVersion(1, spec)] })
    return this.getContract(id)
  }
  async publishContractVersion(contractId: string, spec: string): Promise<PublishedVersion> {
    this.calls.push(`publishContractVersion:${contractId}`)
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    const latest = contract.versions.at(-1)!
    if (latest.specHash === spec) return { version: latest, created: false }
    const version = fakeVersion(latest.versionNumber + 1, spec)
    contract.versions.push(version)
    return { version, created: true }
  }
  async getContract(contractId: string): Promise<Contract> {
    const contract = this.contracts.get(contractId)
    if (!contract) throw new ApiError(404, 'NOT_FOUND', 'unknown contract', 't')
    const serviceName = this.services.find((s) => s.id === contract.serviceId)?.name ?? null
    return { id: contractId, serviceId: contract.serviceId, serviceName, latestVersion: contract.versions.at(-1)! }
  }
  async listContracts(): Promise<Contract[]> {
    return Promise.all([...this.contracts.keys()].map((id) => this.getContract(id)))
  }
  /** Reports each path key the version adds over the previous one; the spec text is in `specHash`. */
  async contractDiff(contractId: string, versionNumber: number): Promise<VersionDiff> {
    const contract = this.contracts.get(contractId)
    const to = contract?.versions[versionNumber - 1]
    if (!contract || !to) throw new ApiError(404, 'NOT_FOUND', 'unknown version', 't')
    const from = contract.versions[versionNumber - 2] ?? null
    const paths = (spec: string) => [...spec.matchAll(/^ {2}"([^"]+)":$/gm)].map((m) => m[1])
    const before = new Set(from ? paths(from.specHash) : [])
    const added = paths(to.specHash).filter((p) => !before.has(p))
    return {
      from,
      to,
      changes: added.map((p) => ({ op: 'added', pointer: `/paths/${p.replaceAll('~', '~0').replaceAll('/', '~1')}`, path: ['paths', p] })),
      lines: added.map((p) => ({ kind: 'added', left: null, right: `  ${p}:` })),
    }
  }
}

function fakeVersion(versionNumber: number, spec: string): ContractVersion {
  return { id: `v-${versionNumber}`, versionNumber, label: /version: "([^"]+)"/.exec(spec)?.[1] ?? null, specHash: spec }
}
