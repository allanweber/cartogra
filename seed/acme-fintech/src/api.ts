import type { Protocol } from './schema.ts'

export interface Team {
  id: string
  name: string
}

export interface CatalogService {
  id: string
  name: string
  description: string | null
  teamId: string | null
  repositoryUrl: string | null
  techStack: string[] | null
  metadata: string | null
  healthStatus: string
  healthEndpoint: string | null
  tier: string | null
  tags: string[] | null
  slaTarget: number | null
  documentationUrl: string | null
  runbookUrl: string | null
}

export interface GraphNode {
  serviceId: string
  name: string
  teamId: string | null
  tier: string | null
}

export interface GraphEdge {
  source: string
  target: string
  dependencyType: string
  protocol: string
}

export interface Graph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
}

export interface Risk {
  type: string
  affectedServices: string[]
}

export interface UnresolvedSpanService {
  name: string
  seenCount: number
}

export interface Drift {
  type: 'UNDECLARED' | 'MISSING'
  sourceServiceId: string
  targetServiceId: string
}

export interface ContractVersion {
  id: string
  versionNumber: number
  label: string | null
  specHash: string
  /** Breaking-change verdict against the previous version; null = unchecked (stored before 5.3). */
  breaking: boolean | null
  /** `discovery` when spec discovery applied the repo's OpenAPI file (story 5.8). */
  source?: 'manual' | 'discovery'
}

export interface Contract {
  id: string
  serviceId: string
  /** Null until contract knows the service's name; contract looks it up from Registry on read. */
  serviceName: string | null
  latestVersion: ContractVersion
}

export interface SpecChange {
  op: 'added' | 'removed' | 'changed'
  pointer: string
  path: string[]
}

export interface DiffLine {
  kind: 'same' | 'added' | 'removed' | 'changed'
  left: string | null
  right: string | null
}

export interface CompatibilityChange {
  rule: string
  breaking: boolean
  direction: 'request' | 'response' | 'operation'
  method: string | null
  path: string
  location: string | null
  message: string
}

export interface Compatibility {
  isBreaking: boolean
  changes: CompatibilityChange[]
}

export interface VersionDiff {
  from: ContractVersion | null
  to: ContractVersion
  changes: SpecChange[]
  lines: DiffLine[]
  compatibility: Compatibility
}

export interface PublishedVersion {
  version: ContractVersion
  /** False when the spec matched the latest version (HTTP 200): nothing was stored. */
  created: boolean
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string
  readonly details: Record<string, unknown>

  constructor(status: number, code: string, message: string, traceId: string, details: Record<string, unknown> = {}) {
    super(`${status} ${code}: ${message} (trace: ${traceId})`)
    this.status = status
    this.code = code
    this.traceId = traceId
    this.details = details
  }
}

/** The slice of the Cartogra gateway API the seed needs. Deliberately has no "create service". */
export interface CartograApi {
  tenantId(): Promise<string>
  listServices(): Promise<CatalogService[]>
  listTeams(): Promise<Team[]>
  createTeam(name: string): Promise<Team>
  updateService(service: CatalogService, tier: string): Promise<void>
  assignOwner(serviceId: string, teamId: string): Promise<void>
  graph(type?: 'DECLARED' | 'OBSERVED'): Promise<Graph>
  declareDependency(sourceServiceId: string, targetServiceId: string, protocol: Protocol): Promise<void>
  cycles(): Promise<string[][]>
  spofs(): Promise<string[]>
  risks(): Promise<Risk[]>
  drifts(): Promise<Drift[]>
  unresolvedSpanServices(): Promise<UnresolvedSpanService[]>
  /** 409 `CONFLICT` with `details.contractId` when the service already has one. */
  createContract(serviceId: string, spec: string): Promise<Contract>
  publishContractVersion(contractId: string, spec: string): Promise<PublishedVersion>
  getContract(contractId: string): Promise<Contract>
  /** `status` keeps the contracts whose latest version is breaking or compatible. */
  listContracts(q?: string, status?: 'breaking' | 'compatible'): Promise<Contract[]>
  contractDiff(contractId: string, versionNumber: number): Promise<VersionDiff>
  listContractVersions(contractId: string): Promise<ContractVersion[]>
}

export interface Cluster {
  id: string
  name: string
}

export interface ScmConnection {
  id: string
  provider: string
}

export interface ClusterCredentials {
  name: string
  apiServerUrl: string
  caCertPem: string
  saToken: string
}

/** Connecting discovery sources (bootstrap only). */
export interface ConnectionsApi {
  listClusters(): Promise<Cluster[]>
  createCluster(credentials: ClusterCredentials): Promise<Cluster>
  listScmConnections(): Promise<ScmConnection[]>
  createGitHubConnection(org: string, token: string): Promise<ScmConnection>
  syncScmConnection(id: string): Promise<void>
}

interface Page<T> {
  items: T[]
  total: number
}

const PAGE_SIZE = 100

export class HttpCartograApi implements CartograApi, ConnectionsApi {
  private readonly baseUrl: string
  private token = ''

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '')
  }

  async login(email: string, password: string): Promise<void> {
    const data = await this.request<{ accessToken: string }>('POST', '/api/auth/login', { email, password })
    this.token = data.accessToken
  }

  async tenantId(): Promise<string> {
    return (await this.request<{ id: string }>('GET', '/api/auth/tenant')).id
  }

  async listServices(): Promise<CatalogService[]> {
    return this.listAll<CatalogService>('/api/v1/registry/services')
  }

  async listTeams(): Promise<Team[]> {
    return this.listAll<Team>('/api/v1/registry/teams')
  }

  createTeam(name: string): Promise<Team> {
    return this.request<Team>('POST', '/api/v1/registry/teams', { name })
  }

  async updateService(s: CatalogService, tier: string): Promise<void> {
    await this.request('PUT', `/api/v1/registry/services/${s.id}`, {
      name: s.name,
      description: s.description,
      repositoryUrl: s.repositoryUrl,
      techStack: s.techStack,
      metadata: s.metadata,
      healthStatus: s.healthStatus,
      healthEndpoint: s.healthEndpoint,
      tier,
      tags: s.tags,
      slaTarget: s.slaTarget,
      documentationUrl: s.documentationUrl,
      runbookUrl: s.runbookUrl,
    })
  }

  async assignOwner(serviceId: string, teamId: string): Promise<void> {
    await this.request('PATCH', `/api/v1/registry/services/${serviceId}/owner`, { teamId })
  }

  graph(type?: 'DECLARED' | 'OBSERVED'): Promise<Graph> {
    const query = type ? `?type=${type}` : ''
    return this.request<Graph>('GET', `/api/v1/topology/graph${query}`)
  }

  async declareDependency(sourceServiceId: string, targetServiceId: string, protocol: Protocol): Promise<void> {
    await this.request('POST', '/api/v1/topology/dependencies', {
      sourceServiceId,
      targetServiceId,
      protocol,
    })
  }

  async cycles(): Promise<string[][]> {
    const data = await this.request<{ cycles: Array<{ members: string[] }> }>('GET', '/api/v1/topology/cycles')
    return data.cycles.map((c) => c.members)
  }

  async spofs(): Promise<string[]> {
    const data = await this.request<{ items: Array<{ name: string }> }>('GET', '/api/v1/topology/spofs')
    return data.items.map((i) => i.name)
  }

  async risks(): Promise<Risk[]> {
    const data = await this.request<Page<Risk>>('GET', '/api/v1/topology/risks?limit=100')
    return data.items
  }

  async drifts(): Promise<Drift[]> {
    return this.listAll<Drift>('/api/v1/topology/drifts')
  }

  async unresolvedSpanServices(): Promise<UnresolvedSpanService[]> {
    return this.listAll<UnresolvedSpanService>('/api/v1/topology/unresolved-span-services')
  }

  createContract(serviceId: string, spec: string): Promise<Contract> {
    return this.request<Contract>('POST', '/api/v1/contracts', { serviceId, spec })
  }

  async publishContractVersion(contractId: string, spec: string): Promise<PublishedVersion> {
    const { data, status } = await this.send<ContractVersion>('POST', `/api/v1/contracts/${contractId}/versions`, { spec })
    return { version: data, created: status === 201 }
  }

  getContract(contractId: string): Promise<Contract> {
    return this.request<Contract>('GET', `/api/v1/contracts/${contractId}`)
  }

  listContracts(q?: string, status?: 'breaking' | 'compatible'): Promise<Contract[]> {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    if (status) params.set('status', status)
    return this.listAll<Contract>(`/api/v1/contracts${params.size > 0 ? `?${params}` : ''}`)
  }

  contractDiff(contractId: string, versionNumber: number): Promise<VersionDiff> {
    return this.request<VersionDiff>('GET', `/api/v1/contracts/${contractId}/versions/${versionNumber}/diff`)
  }

  listContractVersions(contractId: string): Promise<ContractVersion[]> {
    return this.listAll<ContractVersion>(`/api/v1/contracts/${contractId}/versions`)
  }

  listClusters(): Promise<Cluster[]> {
    return this.listAll<Cluster>('/api/v1/ingestion/k8s/clusters')
  }

  createCluster(c: ClusterCredentials): Promise<Cluster> {
    return this.request<Cluster>('POST', '/api/v1/ingestion/k8s/clusters', {
      name: c.name,
      source: 'MANUAL',
      apiServerUrl: c.apiServerUrl,
      caCertPem: c.caCertPem,
      saToken: c.saToken,
      skipTlsVerify: false,
    })
  }

  listScmConnections(): Promise<ScmConnection[]> {
    return this.listAll<ScmConnection>('/api/v1/ingestion/scm-connections')
  }

  createGitHubConnection(org: string, token: string): Promise<ScmConnection> {
    return this.request<ScmConnection>('POST', '/api/v1/ingestion/scm-connections', {
      provider: 'github',
      config: JSON.stringify({ org, token }),
      syncScheduler: true,
      pollIntervalMinutes: 60,
    })
  }

  async syncScmConnection(id: string): Promise<void> {
    await this.request('POST', `/api/v1/ingestion/scm-connections/${id}/sync`)
  }

  private async listAll<T>(path: string): Promise<T[]> {
    const all: T[] = []
    for (;;) {
      const page = await this.request<Page<T>>('GET', `${path}${path.includes('?') ? '&' : '?'}limit=${PAGE_SIZE}&offset=${all.length}`)
      all.push(...page.items)
      if (page.items.length === 0 || all.length >= page.total) return all
    }
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    return (await this.send<T>(method, path, body)).data
  }

  private async send<T>(method: string, path: string, body?: unknown): Promise<{ data: T; status: number }> {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const traceId = response.headers.get('X-Trace-Id') ?? 'unknown'
    const text = await response.text()
    const json = text
      ? (JSON.parse(text) as { data?: T; error?: { code: string; message: string; details?: Record<string, unknown> } })
      : {}
    if (!response.ok) {
      const e = json.error
      throw new ApiError(response.status, e?.code ?? 'UNKNOWN', e?.message ?? response.statusText, traceId, e?.details ?? {})
    }
    return { data: json.data as T, status: response.status }
  }
}
