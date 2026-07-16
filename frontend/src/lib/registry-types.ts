export type ServiceHealth = 'healthy' | 'degraded' | 'down'
export type ServiceTierValue = 'CRITICAL' | 'STANDARD' | 'EXPERIMENTAL'
export type ServiceHealthStatus = 'HEALTHY' | 'DEGRADED' | 'UNHEALTHY' | 'PROBE_AUTH_FAILED' | 'UNKNOWN'
export type ScmSource = 'github' | 'gitlab' | 'azuredevops' | 'bitbucket' | 'kubernetes'

export interface RegistryService {
  id: string
  tenantId: string
  name: string
  description: string | null
  teamId: string | null
  repositoryUrl: string | null
  techStack: string[] | null
  metadata: string | null
  healthStatus: ServiceHealthStatus
  lastDeployedAt: string | null
  createdAt: string
  updatedAt: string
  externalId: string | null
  connectionId: string | null
  source: ScmSource | null
  repositoryRef: string | null
  k8sCluster: string | null
  k8sNamespace: string | null
  k8sDeployment: string | null
  healthEndpoint: string | null
  lastCommitAt: string | null
  lastCommitSha: string | null
  healthCheckedAt: string | null
  tier: ServiceTierValue | null
  tags: string[] | null
  slaTarget: number | null
  documentationUrl: string | null
  runbookUrl: string | null
}

export interface RegistryTeam {
  id: string
  tenantId: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface RegistryTeamMember {
  id: string
  teamId: string
  userId: string
  createdAt: string
}

export interface TenantUser {
  id: string
  email: string
  name: string | null
  roles: string[]
  disabled: boolean
}

export interface PlanInfo {
  name: string
  slug: string
  maxServices: number
  maxUsers: number
  maxApiKeys: number
  maxScmConnections: number
  maxK8sClusters: number
  ssoEnabled: boolean
  rateLimitReplenish: number
  rateLimitBurst: number
}

export interface TenantInfo {
  id: string
  name: string
  slug: string
  plan: PlanInfo
  usersUsed: number
  createdAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

export function normalizeHealth(raw: string): ServiceHealth {
  const upper = raw.toUpperCase()
  if (upper === 'HEALTHY') return 'healthy'
  if (upper === 'DEGRADED') return 'degraded'
  if (upper === 'UNHEALTHY' || upper === 'DOWN') return 'down'
  return 'degraded'
}

export const SCM_LABEL: Record<string, string> = {
  github: 'GitHub',
  gitlab: 'GitLab',
  azuredevops: 'Azure DevOps',
  bitbucket: 'Bitbucket',
  kubernetes: 'Kubernetes',
}
