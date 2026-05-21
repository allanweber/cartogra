export type ServiceHealth = 'healthy' | 'degraded' | 'down'
export type ServiceTier = 'critical' | 'standard'
export type ServiceWarning = 'stale' | 'breaking-change' | 'orphan'

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
}

export type RiskSeverity = 'critical' | 'warning' | 'info'

export interface Risk {
  id: string
  severity: RiskSeverity
  title: string
  services: string[]
  explanation: string
  fix: string
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

export interface GraphNode {
  id: string
  name: string
  health: ServiceHealth
  team: string | null
  tier: ServiceTier
}

export interface GraphEdge {
  from: string
  to: string
  protocol: 'REST' | 'gRPC' | 'Kafka'
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

export const MOCK_SERVICES: Service[] = [
  { id: 'svc-1', name: 'API Gateway', health: 'healthy', owner: 'Platform', tier: 'critical', tech: ['Node.js', 'K8s', 'Nginx'], lastDeploy: '2h ago', riskScore: 12, deps: 11, warnings: [], description: 'Central entry point for all external traffic. Routes and authenticates all API requests.' },
  { id: 'svc-2', name: 'Auth Service', health: 'healthy', owner: 'Security', tier: 'critical', tech: ['Go', 'Redis', 'JWT'], lastDeploy: '1d ago', riskScore: 8, deps: 3, warnings: [], description: 'Handles authentication and authorization for all services. Manages JWT issuance and session tokens.' },
  { id: 'svc-3', name: 'Payment Service', health: 'degraded', owner: 'Payments', tier: 'critical', tech: ['Java', 'Postgres', 'Kafka'], lastDeploy: '5d ago', riskScore: 78, deps: 4, warnings: ['stale', 'breaking-change'], description: 'Processes all payment transactions. Integrates with Stripe and internal billing.' },
  { id: 'svc-4', name: 'User Service', health: 'healthy', owner: 'Core', tier: 'standard', tech: ['Python', 'MySQL', 'Redis'], lastDeploy: '3h ago', riskScore: 24, deps: 5, warnings: [], description: 'Manages user profiles, preferences, and account lifecycle.' },
  { id: 'svc-5', name: 'Notification Service', health: 'healthy', owner: 'Platform', tier: 'standard', tech: ['Node.js', 'Redis', 'SMTP'], lastDeploy: '2d ago', riskScore: 15, deps: 2, warnings: [], description: 'Sends emails, push notifications, and SMS. Supports templates and scheduling.' },
  { id: 'svc-6', name: 'Analytics Engine', health: 'degraded', owner: 'Data', tier: 'standard', tech: ['Python', 'Kafka', 'ClickHouse'], lastDeploy: '12d ago', riskScore: 56, deps: 3, warnings: ['stale'], description: 'Processes and aggregates event data for dashboards and reports.' },
  { id: 'svc-7', name: 'Search Service', health: 'down', owner: null, tier: 'standard', tech: ['Elasticsearch', 'Go'], lastDeploy: '20d ago', riskScore: 92, deps: 1, warnings: ['orphan', 'stale'], description: 'Provides full-text search across products, users, and content.' },
  { id: 'svc-8', name: 'ML Pipeline', health: 'healthy', owner: 'Data', tier: 'standard', tech: ['Python', 'K8s', 'MLflow'], lastDeploy: '1d ago', riskScore: 31, deps: 4, warnings: [], description: 'Trains and serves machine learning models for recommendations and fraud detection.' },
  { id: 'svc-9', name: 'Billing Service', health: 'healthy', owner: 'Payments', tier: 'critical', tech: ['Java', 'Postgres', 'gRPC'], lastDeploy: '6h ago', riskScore: 18, deps: 2, warnings: [], description: 'Manages subscription plans, invoices, and revenue recognition.' },
  { id: 'svc-10', name: 'Config Service', health: 'healthy', owner: 'Platform', tier: 'standard', tech: ['Go', 'etcd', 'gRPC'], lastDeploy: '4d ago', riskScore: 9, deps: 0, warnings: [], description: 'Centralized feature flag and configuration management for all services.' },
  { id: 'svc-11', name: 'Report Service', health: 'healthy', owner: null, tier: 'standard', tech: ['Python', 'PostgreSQL', 'Celery'], lastDeploy: '8d ago', riskScore: 22, deps: 2, warnings: ['orphan'], description: 'Generates scheduled and on-demand reports in PDF and CSV formats.' },
  { id: 'svc-12', name: 'Media Service', health: 'degraded', owner: 'Core', tier: 'standard', tech: ['Node.js', 'S3', 'FFmpeg'], lastDeploy: '15d ago', riskScore: 44, deps: 2, warnings: ['stale'], description: 'Handles image and video upload, transcoding, and CDN distribution.' },
]

export const MOCK_RISKS: Risk[] = [
  { id: 'r-1', severity: 'critical', title: 'Breaking change in Payment API v2.1', services: ['Payment Service', 'Billing Service', 'API Gateway'], explanation: 'The /charge endpoint removed the legacy_mode field, breaking 3 downstream consumers in prod.', fix: 'Pin consumers to v2.0 or update them to handle the new payload format immediately.' },
  { id: 'r-2', severity: 'critical', title: 'Search Service offline — 20 days', services: ['Search Service'], explanation: 'No heartbeat received. 6 consumers are silently swallowing errors due to missing circuit breakers.', fix: 'Investigate Search Service health. Add circuit breaker to all 6 consumers.' },
  { id: 'r-3', severity: 'warning', title: 'Analytics Engine stale — 12 days', services: ['Analytics Engine'], explanation: 'No deployment in 12 days. Direct dependencies on Payment Service have likely drifted.', fix: 'Trigger a sync deployment or mark as deprecated.' },
  { id: 'r-4', severity: 'warning', title: 'Circular dependency: Auth ↔ User', services: ['Auth Service', 'User Service'], explanation: 'Auth depends on User which depends on Auth. This tight coupling makes deploys and incident response risky.', fix: 'Extract shared logic into a common lib or introduce an event bus.' },
  { id: 'r-5', severity: 'warning', title: 'Orphaned services with no owner', services: ['Search Service', 'Report Service'], explanation: '2 services have no active team assignment. Incidents will go unacknowledged.', fix: 'Assign to an active team in Teams > Settings.' },
  { id: 'r-6', severity: 'info', title: 'High fan-in on API Gateway (11 deps)', services: ['API Gateway'], explanation: '11 services depend directly on API Gateway. A single failure would cascade broadly.', fix: 'Consider introducing a service mesh or BFF layer to reduce coupling.' },
  { id: 'r-7', severity: 'info', title: 'Payment Service uses deprecated K8s API', services: ['Payment Service'], explanation: 'The deployment manifest uses apps/v1beta1 removed in K8s 1.16+.', fix: 'Migrate to apps/v1 in the deployment manifest.' },
]

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

export const MOCK_GRAPH_NODES: GraphNode[] = [
  { id: 'g-1', name: 'API Gateway', health: 'healthy', team: 'Platform', tier: 'critical' },
  { id: 'g-2', name: 'Auth Service', health: 'healthy', team: 'Security', tier: 'critical' },
  { id: 'g-3', name: 'Payment Service', health: 'degraded', team: 'Payments', tier: 'critical' },
  { id: 'g-4', name: 'User Service', health: 'healthy', team: 'Core', tier: 'standard' },
  { id: 'g-5', name: 'Notification Service', health: 'healthy', team: 'Platform', tier: 'standard' },
  { id: 'g-6', name: 'Analytics Engine', health: 'degraded', team: 'Data', tier: 'standard' },
  { id: 'g-7', name: 'Search Service', health: 'down', team: null, tier: 'standard' },
  { id: 'g-8', name: 'Billing Service', health: 'healthy', team: 'Payments', tier: 'critical' },
  { id: 'g-9', name: 'Config Service', health: 'healthy', team: 'Platform', tier: 'standard' },
  { id: 'g-10', name: 'ML Pipeline', health: 'healthy', team: 'Data', tier: 'standard' },
]

export const MOCK_GRAPH_EDGES: GraphEdge[] = [
  { from: 'g-1', to: 'g-2', protocol: 'REST' },
  { from: 'g-1', to: 'g-3', protocol: 'REST' },
  { from: 'g-1', to: 'g-4', protocol: 'REST' },
  { from: 'g-1', to: 'g-5', protocol: 'REST' },
  { from: 'g-1', to: 'g-6', protocol: 'REST' },
  { from: 'g-1', to: 'g-7', protocol: 'REST' },
  { from: 'g-3', to: 'g-8', protocol: 'gRPC' },
  { from: 'g-4', to: 'g-2', protocol: 'REST' },
  { from: 'g-4', to: 'g-6', protocol: 'Kafka' },
  { from: 'g-5', to: 'g-6', protocol: 'Kafka' },
  { from: 'g-6', to: 'g-7', protocol: 'REST' },
  { from: 'g-2', to: 'g-4', protocol: 'REST' },
  { from: 'g-9', to: 'g-4', protocol: 'gRPC' },
  { from: 'g-9', to: 'g-3', protocol: 'gRPC' },
  { from: 'g-10', to: 'g-6', protocol: 'Kafka' },
  { from: 'g-8', to: 'g-9', protocol: 'REST' },
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
