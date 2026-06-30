export type ServiceHealth = 'healthy' | 'degraded' | 'down'
export type ScmSource = 'github' | 'gitlab' | 'azuredevops' | 'bitbucket'

export interface RegistryService {
  id: string
  tenantId: string
  name: string
  description: string | null
  teamId: string | null
  repositoryUrl: string | null
  techStack: string[] | null
  metadata: string | null
  healthStatus: string
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
}

export interface RegistryTeam {
  id: string
  tenantId: string
  name: string
  createdAt: string
  updatedAt: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  limit: number
  offset: number
}

export function normalizeHealth(raw: string): ServiceHealth {
  const lower = raw.toLowerCase()
  if (lower === 'healthy') return 'healthy'
  if (lower === 'degraded') return 'degraded'
  if (lower === 'unhealthy' || lower === 'down') return 'down'
  return 'degraded'
}

export const SCM_LABEL: Record<string, string> = {
  github: 'GitHub',
  gitlab: 'GitLab',
  azuredevops: 'Azure DevOps',
  bitbucket: 'Bitbucket',
}
