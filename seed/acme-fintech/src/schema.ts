import { readFileSync } from 'node:fs'

export type Tier = 'CRITICAL' | 'STANDARD' | 'EXPERIMENTAL'

export type RepoProvider = 'github' | 'azure'
export type RepoStack = 'java' | 'node' | 'python'

export interface SeedRepo {
  provider: RepoProvider
  stack: RepoStack
  port: number
}

export interface SeedService {
  name: string
  team: string | null
  tier: Tier
  description: string
  repo: SeedRepo
}

export interface SeedCall {
  from: string
  to: string
  declared: boolean
  enabled: boolean
}

export interface SeedData {
  name: string
  namespace: string
  teams: string[]
  services: SeedService[]
  calls: SeedCall[]
}

const TIERS: readonly Tier[] = ['CRITICAL', 'STANDARD', 'EXPERIMENTAL']
const PROVIDERS: readonly RepoProvider[] = ['github', 'azure']
const STACKS: readonly RepoStack[] = ['java', 'node', 'python']
const MAX_CYCLE_LENGTH = 8

export function loadSeedFile(path: string): SeedData {
  const seed = JSON.parse(readFileSync(path, 'utf8')) as SeedData
  validateSeed(seed)
  return seed
}

export function declaredCalls(seed: SeedData): SeedCall[] {
  return seed.calls.filter((c) => c.declared)
}

export function dormantCalls(seed: SeedData): SeedCall[] {
  return seed.calls.filter((c) => !c.declared)
}

export function dependentsOf(seed: SeedData, target: string): string[] {
  return [...new Set(declaredCalls(seed).filter((c) => c.to === target).map((c) => c.from))]
}

export function enabledCallsFrom(seed: SeedData, service: string): SeedCall[] {
  return seed.calls.filter((c) => c.from === service && c.enabled)
}

export function unownedServices(seed: SeedData): string[] {
  return seed.services.filter((s) => s.team === null).map((s) => s.name)
}

/**
 * Elementary directed cycles, counted like Topology's CycleService: each distinct cycle once,
 * regardless of start node, up to MAX_CYCLE_LENGTH members.
 */
export function findElementaryCycles(edges: Array<[string, string]>): string[][] {
  const adjacency = new Map<string, string[]>()
  for (const [from, to] of edges) {
    adjacency.set(from, [...(adjacency.get(from) ?? []), to])
  }
  const seen = new Set<string>()
  const cycles: string[][] = []

  const walk = (start: string, node: string, path: string[]) => {
    for (const next of adjacency.get(node) ?? []) {
      if (next === start) {
        const key = canonical(path).join('>')
        if (!seen.has(key)) {
          seen.add(key)
          cycles.push(canonical(path))
        }
      } else if (!path.includes(next) && path.length < MAX_CYCLE_LENGTH) {
        walk(start, next, [...path, next])
      }
    }
  }
  for (const start of adjacency.keys()) walk(start, start, [start])
  return cycles
}

function canonical(path: string[]): string[] {
  let min = 0
  for (let i = 1; i < path.length; i++) if (path[i] < path[min]) min = i
  return [...path.slice(min), ...path.slice(0, min)]
}

export function declaredCycles(seed: SeedData): string[][] {
  return findElementaryCycles(declaredCalls(seed).map((c) => [c.from, c.to]))
}

export function allCycles(seed: SeedData): string[][] {
  return findElementaryCycles(seed.calls.map((c) => [c.from, c.to]))
}

export function validateSeed(seed: SeedData): void {
  const errors: string[] = []
  const names = seed.services.map((s) => s.name)
  const lower = names.map((n) => n.toLowerCase())

  if (new Set(lower).size !== lower.length) errors.push('service names must be unique (case-insensitive)')
  if (new Set(seed.teams.map((t) => t.toLowerCase())).size !== seed.teams.length) errors.push('team names must be unique')
  for (const n of names) {
    if (!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(n)) errors.push(`service name "${n}" must be a DNS label`)
  }
  for (const s of seed.services) {
    if (s.team !== null && !seed.teams.includes(s.team)) errors.push(`service ${s.name}: unknown team ${s.team}`)
    if (!TIERS.includes(s.tier)) errors.push(`service ${s.name}: invalid tier ${s.tier}`)
    if (!PROVIDERS.includes(s.repo?.provider)) errors.push(`service ${s.name}: invalid repo provider`)
    if (!STACKS.includes(s.repo?.stack)) errors.push(`service ${s.name}: invalid repo stack`)
  }
  const ports = seed.services.map((s) => s.repo?.port)
  if (new Set(ports).size !== ports.length) errors.push('repo ports must be unique')
  const keys = new Set<string>()
  for (const c of seed.calls) {
    if (!names.includes(c.from)) errors.push(`call ${c.from}->${c.to}: unknown source`)
    if (!names.includes(c.to)) errors.push(`call ${c.from}->${c.to}: unknown target`)
    if (c.from === c.to) errors.push(`call ${c.from}: self call`)
    const key = `${c.from}>${c.to}`
    if (keys.has(key)) errors.push(`call ${key}: duplicate`)
    keys.add(key)
    if (c.declared && !c.enabled) errors.push(`call ${key}: declared calls must be enabled`)
  }
  if (errors.length > 0) throw new Error(`Invalid seed data:\n- ${errors.join('\n- ')}`)
}
