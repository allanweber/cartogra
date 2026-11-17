import { expect, test } from '@playwright/test'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

function spec(version: string, extraPath = '') {
  return `openapi: 3.0.3
info:
  title: e2e
  version: ${version}
paths:
  /entries:
    get:
      responses:
        '200':
          description: ok
${extraPath}`
}

const HISTORY_PATH = `  /entries/{id}/history:
    get:
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        '200':
          description: ok
`

// There is no spec upload UI in 5.2, so versions are posted through the gateway; the list, timeline and
// diff are then driven through the UI against the real contract service and its service-name projection.
test('contract list, version timeline and side-by-side diff', async ({ page }) => {
  const name = uniqueName('ledger')

  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()
  await page.getByRole('button', { name: 'Register service' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Service name').fill(name)
  await dialog.getByRole('button', { name: 'Register service' }).click()
  await expect(dialog).not.toBeVisible()
  const href = await page.getByRole('link', { name }).getAttribute('href')
  const serviceId = href!.split('/').pop()!

  const created = await page.request.post('/api/v1/contracts', { data: { serviceId, spec: spec('1.0.0') } })
  expect(created.status()).toBe(201)
  const contractId = (await created.json()).data.id as string
  const published = await page.request.post(`/api/v1/contracts/${contractId}/versions`, {
    data: { spec: spec('1.1.0', HISTORY_PATH) },
  })
  expect(published.status()).toBe(201)

  await page.goto(`/contracts?q=${encodeURIComponent(name)}`)
  await expect(async () => {
    await page.reload()
    await expect(page.getByRole('link', { name })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000 })
  await expect(page.getByText('v2 · 1.1.0')).toBeVisible()

  await page.getByRole('link', { name }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()

  const timeline = page.getByRole('list', { name: 'Version timeline' })
  await expect(timeline.getByRole('button', { name: /v2/ })).toHaveAttribute('aria-current', 'true')
  await expect(timeline.getByRole('button', { name: /v1/ })).toBeVisible()

  const changes = page.getByRole('list', { name: 'Changes' })
  await expect(changes.getByText('paths › /entries/{id}/history')).toBeVisible()
  await expect(changes.getByText('1.0.0 → 1.1.0')).toBeVisible()
  const diff = page.getByRole('table', { name: 'Diff v1 to v2' })
  await expect(diff.locator('tr[data-kind="added"]').filter({ hasText: '/entries/{id}/history' })).toHaveCount(1)

  await timeline.getByRole('button', { name: /v1/ }).click()
  await expect(page).toHaveURL(/v=1/)
  await expect(page.getByRole('table', { name: 'Diff empty to v1' })).toBeVisible()
})
