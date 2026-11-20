import type { PageResult } from '#/lib/registry-types'

export interface ContractVersion {
  id: string
  contractId: string
  versionNumber: number
  label: string | null
  specHash: string
  /** Engine verdict against the previous version; null = unchecked (stored before 5.3). */
  breaking: boolean | null
  createdAt: string
  /** `discovery` when spec discovery applied a file from an SCM repository. */
  source: 'manual' | 'discovery'
  /** The repository and file of a discovered version; null for a manual version. */
  repositoryFullName: string | null
  filePath: string | null
}

export interface ContractSummary {
  id: string
  serviceId: string
  /** Null until contract's projection of Registry has seen the service. */
  serviceName: string | null
  latestVersion: ContractVersion
  updatedAt: string
}

export interface Contract {
  id: string
  serviceId: string
  serviceName: string | null
  specType: 'openapi'
  createdAt: string
  updatedAt: string
  latestVersion: ContractVersion
}

export type ContractsPage = PageResult<ContractSummary>
export type ContractVersionsPage = PageResult<ContractVersion>

export interface SpecChange {
  op: 'added' | 'removed' | 'changed'
  /** RFC 6901 pointer into the canonical spec. */
  pointer: string
  /** The pointer's unescaped segments. */
  path: string[]
  before: unknown
  after: unknown
}

export interface DiffLine {
  kind: 'same' | 'added' | 'removed' | 'changed'
  leftNo: number | null
  left: string | null
  rightNo: number | null
  right: string | null
}

export const COMPATIBILITY_STATUSES = ['breaking', 'compatible'] as const

export type CompatibilityStatus = (typeof COMPATIBILITY_STATUSES)[number]

export interface CompatibilityChange {
  /** Rule id, e.g. `path-removed`. */
  rule: string
  breaking: boolean
  direction: 'request' | 'response' | 'operation'
  method: string | null
  path: string
  /** Field path such as `requestBody.items[].sku` or `query.currency`; null for path and operation rules. */
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
