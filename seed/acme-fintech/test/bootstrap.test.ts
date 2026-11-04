import { describe, expect, it } from 'vitest'
import type { Cluster, ClusterCredentials, ConnectionsApi, ScmConnection } from '../src/api.ts'
import { connectSources, retry } from '../src/bootstrap.ts'

class FakeConnections implements ConnectionsApi {
  clusters: Cluster[] = []
  scm: ScmConnection[] = []
  readonly calls: string[] = []

  async listClusters() {
    return [...this.clusters]
  }
  async createCluster(c: ClusterCredentials) {
    this.calls.push(`createCluster:${c.name}`)
    const cluster = { id: `c-${c.name}`, name: c.name }
    this.clusters.push(cluster)
    return cluster
  }
  async listScmConnections() {
    return [...this.scm]
  }
  async createGitHubConnection(org: string) {
    this.calls.push(`createGitHub:${org}`)
    const connection = { id: 'scm-1', provider: 'github' }
    this.scm.push(connection)
    return connection
  }
  async syncScmConnection(id: string) {
    this.calls.push(`sync:${id}`)
  }
}

const cluster: ClusterCredentials = { name: 'demo', apiServerUrl: 'https://kubernetes.default.svc', caCertPem: 'ca', saToken: 't' }

describe('connectSources', () => {
  it('registers the cluster and github once, syncing every run', async () => {
    const api = new FakeConnections()
    await connectSources(api, { cluster, github: { org: 'acme', token: 'x' } })
    await connectSources(api, { cluster, github: { org: 'acme', token: 'x' } })

    expect(api.calls).toEqual(['createCluster:demo', 'createGitHub:acme', 'sync:scm-1', 'sync:scm-1'])
  })

  it('skips github without org/token', async () => {
    const api = new FakeConnections()
    await connectSources(api, { cluster })

    expect(api.calls).toEqual(['createCluster:demo'])
  })
})

describe('retry', () => {
  it('retries until success', async () => {
    let attempts = 0
    const result = await retry(
      async () => {
        if (++attempts < 3) throw new Error('not yet')
        return 'ok'
      },
      { timeoutMs: 1000, intervalMs: 1, sleep: async () => {} },
    )
    expect(result).toBe('ok')
    expect(attempts).toBe(3)
  })

  it('rethrows the last error after the deadline', async () => {
    let clock = 0
    await expect(
      retry(
        async () => {
          throw new Error('down')
        },
        { timeoutMs: 10, intervalMs: 5, sleep: async (ms) => void (clock += ms), now: () => clock },
      ),
    ).rejects.toThrow('down')
  })
})
