export type ServiceHealth = 'healthy' | 'degraded' | 'down'
export type ServiceTier = 'critical' | 'standard'
export type ServiceWarning = 'stale' | 'breaking-change' | 'orphan'
export type ScmProvider = 'github' | 'gitlab' | 'azure-devops' | 'bitbucket'

export interface Service {
  id: string
  name: string
  health: ServiceHealth
  owner: string | null
  tier: ServiceTier
  tech: string[]
  lastDeploy: string
  riskScore: number
  deps: number
  warnings: ServiceWarning[]
  description: string
  scmProvider: ScmProvider
}

export type ContractStatus = 'compatible' | 'breaking' | 'warning' | 'stale'

export interface Contract {
  id: string
  name: string
  service: string
  version: string
  status: ContractStatus
  consumers: number
  lastChanged: string
}

export type RiskExposure = 'low' | 'medium' | 'high' | 'critical'

export interface Team {
  id: string
  name: string
  members: number
  services: string[]
  health: ServiceHealth
  riskExposure: RiskExposure
  lead: string
}

export type TimelineEventType = 'deploy' | 'contract' | 'risk' | 'ownership' | 'dependency'

export interface TimelineEvent {
  id: string
  type: TimelineEventType
  service: string
  msg: string
  time: string
  actor: string
  team: string | null
}

export type OperationsEventType = 'github' | 'k8s' | 'otel' | 'azure'
export type OperationsEventStatus = 'success' | 'error' | 'warning' | 'info'

export interface OperationsEvent {
  id: string
  type: OperationsEventType
  msg: string
  time: string
  status: OperationsEventStatus
}

export const MOCK_CONTRACTS: Contract[] = [
  { id: 'c-1', name: 'payment-api', service: 'Payment Service', version: 'v2.1', status: 'breaking', consumers: 3, lastChanged: '4h ago' },
  { id: 'c-2', name: 'auth-api', service: 'Auth Service', version: 'v1.4', status: 'compatible', consumers: 8, lastChanged: '7d ago' },
  { id: 'c-3', name: 'user-api', service: 'User Service', version: 'v3.0', status: 'compatible', consumers: 5, lastChanged: '3d ago' },
  { id: 'c-4', name: 'notification-api', service: 'Notification Service', version: 'v1.2', status: 'warning', consumers: 4, lastChanged: '2d ago' },
  { id: 'c-5', name: 'analytics-api', service: 'Analytics Engine', version: 'v2.0', status: 'stale', consumers: 2, lastChanged: '12d ago' },
  { id: 'c-6', name: 'search-api', service: 'Search Service', version: 'v1.8', status: 'breaking', consumers: 6, lastChanged: '20d ago' },
  { id: 'c-7', name: 'billing-api', service: 'Billing Service', version: 'v1.1', status: 'compatible', consumers: 2, lastChanged: '1d ago' },
  { id: 'c-8', name: 'config-api', service: 'Config Service', version: 'v2.3', status: 'compatible', consumers: 9, lastChanged: '4d ago' },
]

export const MOCK_TEAMS: Team[] = [
  { id: 't-1', name: 'Platform', members: 6, services: ['API Gateway', 'Notification Service', 'Config Service'], health: 'healthy', riskExposure: 'low', lead: 'Sarah K.' },
  { id: 't-2', name: 'Security', members: 4, services: ['Auth Service'], health: 'healthy', riskExposure: 'low', lead: 'Marcus T.' },
  { id: 't-3', name: 'Payments', members: 5, services: ['Payment Service', 'Billing Service'], health: 'degraded', riskExposure: 'critical', lead: 'Jamie L.' },
  { id: 't-4', name: 'Core', members: 8, services: ['User Service', 'Media Service'], health: 'healthy', riskExposure: 'medium', lead: 'Alice W.' },
  { id: 't-5', name: 'Data', members: 7, services: ['Analytics Engine', 'ML Pipeline', 'Report Service'], health: 'degraded', riskExposure: 'high', lead: 'Bob R.' },
]

export const MOCK_TIMELINE: TimelineEvent[] = [
  { id: 'tl-1', type: 'deploy', service: 'API Gateway', msg: 'Deployed v3.2.1 — performance patch', time: '2h ago', actor: 'ci-bot', team: 'Platform' },
  { id: 'tl-2', type: 'contract', service: 'Payment Service', msg: 'Breaking change in payment-api v2.1', time: '4h ago', actor: 'alice@corp.com', team: 'Payments' },
  { id: 'tl-3', type: 'deploy', service: 'Auth Service', msg: 'Deployed v1.9.3 — security patch', time: '1d ago', actor: 'ci-bot', team: 'Security' },
  { id: 'tl-4', type: 'risk', service: 'Search Service', msg: 'Service went offline — no heartbeat', time: '20d ago', actor: 'system', team: null },
  { id: 'tl-5', type: 'ownership', service: 'Report Service', msg: 'Owner removed — service now unowned', time: '3d ago', actor: 'admin@corp.com', team: 'Data' },
  { id: 'tl-6', type: 'dependency', service: 'User Service', msg: 'Added dependency on Config Service', time: '5d ago', actor: 'bob@corp.com', team: 'Core' },
  { id: 'tl-7', type: 'deploy', service: 'ML Pipeline', msg: 'Deployed v2.1.0 — new recommendation model', time: '1d ago', actor: 'ci-bot', team: 'Data' },
  { id: 'tl-8', type: 'contract', service: 'Auth Service', msg: 'auth-api updated to v1.4 — new scopes added', time: '7d ago', actor: 'alice@corp.com', team: 'Security' },
  { id: 'tl-9', type: 'deploy', service: 'Billing Service', msg: 'Deployed v1.1.5 — invoice timezone fix', time: '6h ago', actor: 'ci-bot', team: 'Payments' },
  { id: 'tl-10', type: 'dependency', service: 'Analytics Engine', msg: 'Removed dependency on legacy Event Bus', time: '8d ago', actor: 'bob@corp.com', team: 'Data' },
]

export const MOCK_EVENTS: OperationsEvent[] = [
  { id: 'e-1', type: 'github', msg: 'Push to main: api-gateway — 3 files changed', time: '2 min ago', status: 'success' },
  { id: 'e-2', type: 'k8s', msg: 'Pod restart: payment-service (OOMKilled x2)', time: '8 min ago', status: 'error' },
  { id: 'e-3', type: 'github', msg: 'PR merged: auth-service — security patch', time: '1h ago', status: 'success' },
  { id: 'e-4', type: 'otel', msg: 'P99 latency spike: payment-service (820ms)', time: '22 min ago', status: 'warning' },
  { id: 'e-5', type: 'azure', msg: 'Pipeline run: billing-service — 14/14 passed', time: '6h ago', status: 'success' },
  { id: 'e-6', type: 'k8s', msg: 'Deployment rollout: api-gateway v3.2.1 complete', time: '2h ago', status: 'success' },
  { id: 'e-7', type: 'otel', msg: 'Error rate above 5%: search-service', time: '20d ago', status: 'error' },
  { id: 'e-8', type: 'github', msg: 'Branch created: ml-pipeline — feature/fraud-v3', time: '2h ago', status: 'info' },
]
