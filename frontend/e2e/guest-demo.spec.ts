import { readFile } from 'node:fs/promises'

import { expect, request, test } from '@playwright/test'
import { Client } from 'pg'

import { runInfoPath } from './global-setup'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:3016'
const dbUrl = process.env.E2E_DB_URL ?? 'postgresql://cartogra:cartogra@localhost:5436/cartogra'
// Must match GUEST_TENANT_ID on the gateway (infra/docker-compose/docker-compose.yml).
const demoTenantId = process.env.E2E_GUEST_TENANT_ID ?? '00000000-0000-4000-8000-00000000de30'
const password = 'Cartogra-e2e-1!'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

/**
 * Seeds the demo tenant the way a real member would — through the API — so the guest sees genuine
 * audit events and topology. The seeding admin reuses the e2e user's password hash, so it can log in.
 */
async function seedDemoTenant(upstream: string, downstream: string) {
  const { email: e2eEmail } = JSON.parse(await readFile(runInfoPath, 'utf8')) as { email: string }
  const seedEmail = `demo-seed-${Date.now()}@cartogra.test`

  const client = new Client({ connectionString: dbUrl })
  await client.connect()
  try {
    await client.query(
      `INSERT INTO registry.tenants (tenant_id, name, slug, plan_id)
       SELECT $1::uuid, 'Acme (demo)', 'acme-demo-e2e', (SELECT id FROM registry.billing_plans WHERE slug = 'business')
       WHERE NOT EXISTS (SELECT 1 FROM registry.tenants WHERE tenant_id = $1::uuid)`,
      [demoTenantId],
    )
    await client.query(
      `INSERT INTO registry.users (tenant_id, email, auth_provider, password_hash, email_verified, roles, created_at, updated_at)
       SELECT $1::uuid, $2, 'local', password_hash, true, '{ADMIN}', now(), now()
       FROM registry.users WHERE email = $3`,
      [demoTenantId, seedEmail, e2eEmail],
    )
  } finally {
    await client.end()
  }

  const api = await request.newContext({ baseURL, storageState: { cookies: [], origins: [] } })
  try {
    const login = await api.post('/api/auth/login', { data: { email: seedEmail, password } })
    expect(login.ok(), await login.text()).toBe(true)

    const ids: string[] = []
    for (const name of [upstream, downstream]) {
      const created = await api.post('/api/v1/registry/services', { data: { name } })
      expect(created.ok(), await created.text()).toBe(true)
      ids.push(((await created.json()) as { data: { id: string } }).data.id)
    }
    await expect(async () => {
      const dependency = await api.post('/api/v1/topology/dependencies', {
        data: { sourceServiceId: ids[0], targetServiceId: ids[1], protocol: 'HTTP' },
      })
      expect(dependency.status(), await dependency.text()).toBe(201)
    }).toPass({ timeout: 20_000, intervals: [1_000] })
  } finally {
    await api.dispose()
  }
}

test.describe('guest demo', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('a stranger reads the demo tenant but cannot change it', async ({ page }) => {
    const upstream = uniqueName('demo-checkout')
    const downstream = uniqueName('demo-payments')
    await seedDemoTenant(upstream, downstream)

    await page.goto('/login')
    await page.getByRole('link', { name: 'Try the demo' }).click()
    await expect(page).toHaveURL(/\/dashboard$/)
    await expect(page.getByRole('status').filter({ hasText: /read-only demo/i })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Settings', exact: true })).toHaveCount(0)

    await expect(page.getByTestId('activity-event').filter({ hasText: downstream }).first()).toBeVisible()

    // Blast radius reads the debounced graph view, so retry until the new edge is in it.
    await expect(async () => {
      await page.getByRole('link', { name: 'Dashboard', exact: true }).click()
      await page.getByRole('link', { name: 'Graph', exact: true }).click()
      const node = page.getByRole('button', { name: new RegExp(`^${downstream},`) })
      await node.locator('circle.graph-node-hit').click({ timeout: 2_000 })
      await expect(page.getByRole('tabpanel').getByText(upstream).first()).toBeVisible({ timeout: 2_000 })
    }).toPass({ timeout: 30_000, intervals: [2_000] })

    await page.getByRole('link', { name: 'Catalog', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Register service' })).toBeDisabled()

    const write = await page.request.post('/api/v1/registry/services', { data: { name: uniqueName('guest-write') } })
    expect(write.status()).toBe(403)
    const userinfo = await page.request.get('/api/auth/userinfo')
    expect(((await userinfo.json()) as { data: { roles: string[] } }).data.roles).toEqual(['GUEST'])
  })
})
