import type { PageResult } from '#/lib/registry-types'

export interface ContractVersion {
  id: string
  contractId: string
  versionNumber: number
  label: string | null
  specHash: string
  createdAt: string
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

export interface VersionDiff {
  from: ContractVersion | null
  to: ContractVersion
  changes: SpecChange[]
  lines: DiffLine[]
}
