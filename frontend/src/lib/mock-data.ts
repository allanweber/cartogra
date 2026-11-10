// Fixtures for screens not yet backed by an API (Contracts, Phase 5). Delete with the screen's real data.
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
