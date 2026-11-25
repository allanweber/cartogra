import { expect, test } from '@playwright/test'
import type { Page, Response } from '@playwright/test'
import { Client } from 'pg'

const dbUrl = process.env.E2E_DB_URL ?? 'postgresql://cartogra:cartogra@localhost:5436/cartogra'
const runId = Date.now()
const keyName = `e2e-github-ci-${runId}`

function isGet(path: string) {
  return (r: Response) =>
    r.ok() && r.request().method() === 'GET' && new URL(r.url()).pathname === `/api${path}`
}

async function itemCount(response: Response): Promise<number> {
  const body = (await response.json()) as { data: { items: unknown[] } }
  return body.data.items.length
}

/** A full page load opens the onboarding wizard when the tenant has no connection and no cluster. Close it then. */
async function openApiKeys(page: Page) {
  const connections = page.waitForResponse(isGet('/v1/ingestion/scm-connections'))
  const clusters = page.waitForResponse(isGet('/v1/ingestion/k8s/clusters'))
  await page.goto('/settings/api-keys')
  const [connectionsRes, clustersRes] = await Promise.all([connections, clusters])
  if ((await itemCount(connectionsRes)) === 0 && (await itemCount(clustersRes)) === 0) {
    await page.getByRole('button', { name: 'Skip setup' }).click()
  }
  await expect(page.getByRole('dialog')).toHaveCount(0)
}

async function fetchInviteToken(email: string): Promise<string> {
  const client = new Client({ connectionString: dbUrl })
  await client.connect()
  try {
    const result = await client.query<{ token: string }>(
      'SELECT token FROM registry.invitations WHERE email = $1 ORDER BY created_at DESC LIMIT 1',
      [email],
    )
    const token = result.rows[0]?.token
    if (!token) throw new Error(`Invitation token for ${email} not found`)
    return token
  } finally {
    await client.end()
  }
}

test('an admin creates a key, sees it one time, sees only the prefix, then revokes it', async ({ page }) => {
  await openApiKeys(page)

  await page.getByRole('button', { name: 'Create key' }).click()
  const create = page.getByRole('dialog', { name: 'Create API key' })
  await create.getByLabel('Name').fill(keyName)
  await create.getByLabel('ci:check').check()
  await create.getByRole('button', { name: 'Create key' }).click()

  const reveal = page.getByRole('dialog', { name: 'Copy your API key' })
  const secret = reveal.getByTestId('api-key-secret')
  await expect(secret).toHaveText(/^cgk_/)
  const fullKey = (await secret.textContent()) ?? ''
  await expect(reveal.getByRole('button', { name: 'Copy', exact: true })).toBeVisible()
  const workflow = reveal.getByTestId('api-key-workflow')
  await expect(workflow).toContainText('uses: allanweber/cartogra-contract-check@v1')
  await expect(workflow).toContainText('secrets.CARTOGRA_API_KEY')
  await expect(workflow).not.toContainText(fullKey)
  await expect(reveal.getByRole('button', { name: 'Copy workflow' })).toBeVisible()

  await reveal.getByRole('button', { name: 'Done' }).click()
  await expect(reveal).toHaveCount(0)

  const row = page.getByRole('row').filter({ hasText: keyName })
  await expect(row).toBeVisible()
  await expect(row.getByText('ci:check')).toBeVisible()
  await expect(row.getByText('active')).toBeVisible()
  await expect(page.getByText(fullKey)).toHaveCount(0)
  const prefix = fullKey.slice(0, 12)
  await expect(row.getByText(`${prefix}…`, { exact: false })).toBeVisible()

  await row.getByRole('button', { name: 'Revoke' }).click()
  const confirm = page.getByRole('dialog', { name: 'Revoke API key' })
  await confirm.getByRole('button', { name: 'Revoke' }).click()
  await expect(row.getByText('revoked')).toBeVisible()
  await expect(row.getByRole('button', { name: 'Revoke' })).toHaveCount(0)
})

test('a viewer cannot create or revoke a key and sees no actions', async ({ page, browser }) => {
  const viewerEmail = `e2e-viewer-${runId}@cartogra.test`
  const password = 'Cartogra-e2e-1!'
  const invite = await page.request.post('/api/auth/admin/invite', {
    data: { email: viewerEmail, role: 'VIEWER' },
  })
  expect(invite.ok(), await invite.text()).toBeTruthy()
  const token = await fetchInviteToken(viewerEmail)

  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } })
  try {
    const accept = await context.request.post('/api/auth/accept-invite', { data: { token, password } })
    expect(accept.ok(), await accept.text()).toBeTruthy()

    // The Settings layout blocks non-admins in the UI, so the page shows no list and no action.
    const viewer = await context.newPage()
    await viewer.goto('/settings/api-keys')
    await expect(viewer.getByText('You need the Admin role to access settings.')).toBeVisible()
    await expect(viewer.getByRole('button', { name: 'Create key' })).toHaveCount(0)
    await expect(viewer.getByRole('button', { name: 'Revoke' })).toHaveCount(0)

    // The API keeps BR-1: a viewer can read the list and cannot create a key.
    const list = await context.request.get('/api/v1/registry/api-keys')
    expect(list.status()).toBe(200)
    const create = await context.request.post('/api/v1/registry/api-keys', {
      data: { name: `e2e-viewer-denied-${runId}`, scopes: ['ci:check'] },
    })
    expect(create.status()).toBe(403)
  } finally {
    await context.close()
  }
})
