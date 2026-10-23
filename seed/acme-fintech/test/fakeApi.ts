import { ApiError, type CartograApi, type CatalogService, type Drift, type Graph, type Risk, type Team, type UnresolvedSpanService } from '../src/api.ts'
import { declaredCycles, type SeedData } from '../src/schema.ts'

export class FakeApi implements CartograApi {
  readonly teams: Team[] = []
  readonly services: CatalogService[]
  readonly edges = new Set<string>()
  readonly calls: string[] = []
  driftRecords: Drift[] = []
  unresolved: UnresolvedSpanService[] = []
  nodeLagReads = 0
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
    const edges =
      type === 'OBSERVED'
        ? []
        : [...this.edges].map((e) => {
            const [source, target] = e.split('>')
            return { source, target, dependencyType: 'declared', protocol: 'http' }
          })
    return { nodes, edges, truncated: false }
  }
  async declareDependency(source: string, target: string) {
    this.calls.push(`declare:${source}>${target}`)
    if (!this.services.some((s) => s.id === source) || !this.services.some((s) => s.id === target)) {
      throw new ApiError(404, 'NOT_FOUND', 'unknown node', 't')
    }
    const key = `${source}>${target}`
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
}
