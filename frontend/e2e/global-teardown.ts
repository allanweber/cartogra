import { readFile, rm } from 'node:fs/promises'

import { Client } from 'pg'

import { runInfoPath } from './global-setup'

const dbUrl = process.env.E2E_DB_URL ?? 'postgresql://cartogra:cartogra@localhost:5436/cartogra'
const E2E_TENANT_PREFIX = 'E2E Tenant '

// Hard-deletes everything one e2e run created. Every registry/topology table carries tenant_id,
// so the tables are discovered rather than listed, and FK order is bypassed for the delete (the
// e2e DB role is a superuser). Only tenants named "E2E Tenant …" are ever matched.
export async function purgeE2eTenants(client: Client, orgNames: string[] | 'all') {
  const tenants = await client.query<{ tenant_id: string }>(
    orgNames === 'all'
      ? 'SELECT tenant_id FROM registry.tenants WHERE name LIKE $1'
      : 'SELECT tenant_id FROM registry.tenants WHERE name = ANY($2) AND name LIKE $1',
    orgNames === 'all' ? [`${E2E_TENANT_PREFIX}%`] : [`${E2E_TENANT_PREFIX}%`, orgNames],
  )
  const tenantIds = tenants.rows.map((row) => row.tenant_id)
  if (tenantIds.length === 0) return 0

  const tables = await client.query<{ table_schema: string; table_name: string }>(
    `SELECT table_schema, table_name FROM information_schema.columns
     WHERE column_name = 'tenant_id' AND table_schema IN ('registry', 'topology')`,
  )

  await client.query('BEGIN')
  try {
    await client.query("SET LOCAL session_replication_role = 'replica'")
    for (const { table_schema, table_name } of tables.rows) {
      await client.query(`DELETE FROM "${table_schema}"."${table_name}" WHERE tenant_id = ANY($1::uuid[])`, [tenantIds])
    }
    await client.query('COMMIT')
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  }
  await client.query('REFRESH MATERIALIZED VIEW CONCURRENTLY topology.dependency_graph_edges')
  return tenantIds.length
}

export default async function globalTeardown() {
  if (process.env.E2E_KEEP_DATA === '1') return

  let orgNames: string[] | 'all' = []
  if (process.env.E2E_PURGE_STALE === '1') {
    orgNames = 'all'
  } else {
    try {
      const info = JSON.parse(await readFile(runInfoPath, 'utf8')) as { orgName: string }
      orgNames = [info.orgName]
    } catch {
      return
    }
  }

  const client = new Client({ connectionString: dbUrl })
  await client.connect()
  try {
    const purged = await purgeE2eTenants(client, orgNames)
    console.log(`[e2e] purged ${purged} e2e tenant(s) from the database`)
  } finally {
    await client.end()
    await rm(runInfoPath, { force: true })
  }
}
