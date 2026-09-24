import type { ServiceHealthStatus, ServiceTierValue } from '#/lib/registry-types'

export type DependencyType = 'DECLARED' | 'OBSERVED'
export type DependencyProtocol = 'HTTP' | 'GRPC' | 'KAFKA' | 'DB'

export interface GraphNode {
  serviceId: string
  name: string
  teamId: string | null
  tier: ServiceTierValue | null
  healthStatus: ServiceHealthStatus
}

export interface GraphEdge {
  source: string
  target: string
  dependencyType: DependencyType
  protocol: DependencyProtocol
  metadata: string | null
}

export interface Graph {
  nodes: GraphNode[]
  edges: GraphEdge[]
  truncated: boolean
}

export interface DependencyDirectionEntry {
  id: string
  serviceId: string
  name: string
  teamId: string | null
  tier: ServiceTierValue | null
  healthStatus: ServiceHealthStatus
  protocol: DependencyProtocol
  metadata: string | null
  createdAt: string
  updatedAt: string
}

export interface ServiceDependencies {
  upstream: DependencyDirectionEntry[]
  downstream: DependencyDirectionEntry[]
}

export interface DependencyResponse {
  id: string
  sourceServiceId: string
  targetServiceId: string
  type: DependencyType
  protocol: DependencyProtocol
  metadata: string | null
  createdAt: string
  updatedAt: string
}

export interface DeclareDependencyRequest {
  sourceServiceId: string
  targetServiceId: string
  protocol: DependencyProtocol
  metadata: string | null
}
