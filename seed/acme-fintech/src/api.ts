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

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId: string

  constructor(status: number, code: string, message: string, traceId: string) {
    super(`${status} ${code}: ${message} (trace: ${traceId})`)
    this.status = status
    this.code = code
    this.traceId = traceId
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
  declareDependency(sourceServiceId: string, targetServiceId: string): Promise<void>
  cycles(): Promise<string[][]>
  spofs(): Promise<string[]>
  risks(): Promise<Risk[]>
}

interface Page<T> {
  items: T[]
  total: number
}

const PAGE_SIZE = 100

export class HttpCartograApi implements CartograApi {
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

  async declareDependency(sourceServiceId: string, targetServiceId: string): Promise<void> {
    await this.request('POST', '/api/v1/topology/dependencies', {
      sourceServiceId,
      targetServiceId,
      protocol: 'HTTP',
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

  private async listAll<T>(path: string): Promise<T[]> {
    const all: T[] = []
    for (;;) {
      const page = await this.request<Page<T>>('GET', `${path}?limit=${PAGE_SIZE}&offset=${all.length}`)
      all.push(...page.items)
      if (page.items.length === 0 || all.length >= page.total) return all
    }
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
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
    const json = text ? (JSON.parse(text) as { data?: T; error?: { code: string; message: string } }) : {}
    if (!response.ok) {
      throw new ApiError(response.status, json.error?.code ?? 'UNKNOWN', json.error?.message ?? response.statusText, traceId)
    }
    return json.data as T
  }
}
