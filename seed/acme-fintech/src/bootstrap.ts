import type { ClusterCredentials, ConnectionsApi } from './api.ts'

export interface GitHubSource {
  org: string
  token: string
}

export interface ConnectOptions {
  cluster: ClusterCredentials
  github?: GitHubSource
  log?: (message: string) => void
}

/** Idempotent: registers the cluster and the GitHub connection only when none with that name/provider exists. */
export async function connectSources(api: ConnectionsApi, options: ConnectOptions): Promise<void> {
  const log = options.log ?? (() => {})

  const clusters = await api.listClusters()
  if (clusters.some((c) => c.name === options.cluster.name)) {
    log(`cluster exists: ${options.cluster.name}`)
  } else {
    await api.createCluster(options.cluster)
    log(`cluster registered: ${options.cluster.name}`)
  }

  if (!options.github) {
    log('github: skipped (no org/token)')
    return
  }
  const existing = (await api.listScmConnections()).find((c) => c.provider === 'github')
  const connection = existing ?? (await api.createGitHubConnection(options.github.org, options.github.token))
  log(existing ? `github connection exists: ${options.github.org}` : `github connected: ${options.github.org}`)
  await api.syncScmConnection(connection.id)
  log('github sync triggered')
}

/** Retries `attempt` until it resolves or `timeoutMs` passes; rethrows the last error. */
export async function retry<T>(
  attempt: () => Promise<T>,
  options: { timeoutMs: number; intervalMs: number; log?: (m: string) => void; sleep?: (ms: number) => Promise<void>; now?: () => number },
): Promise<T> {
  const sleep = options.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)))
  const now = options.now ?? Date.now
  const deadline = now() + options.timeoutMs
  for (;;) {
    try {
      return await attempt()
    } catch (error) {
      if (now() >= deadline) throw error
      options.log?.(`waiting: ${error instanceof Error ? error.message : String(error)}`)
      await sleep(options.intervalMs)
    }
  }
}
