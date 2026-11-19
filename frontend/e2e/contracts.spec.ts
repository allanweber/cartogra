import { expect, test, type Page } from '@playwright/test'

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

async function registerService(page: Page, name: string) {
  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()
  await page.getByRole('button', { name: 'Register service' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Service name').fill(name)
  await dialog.getByRole('button', { name: 'Register service' }).click()
  await expect(dialog).not.toBeVisible()
  // The accessible name of a catalog card is the service name and then the card details, so match its start.
  const href = await page.getByRole('link', { name: new RegExp(`^${name}\\b`) }).getAttribute('href')
  return href!.split('/').pop()!
}

// There is no spec upload UI in 5.2, so versions are posted through the gateway; the list, timeline and
// diff are then driven through the UI against the real contract service and its service-name projection.
test('contract list, version timeline and side-by-side diff', async ({ page }) => {
  const name = uniqueName('ledger')
  const serviceId = await registerService(page, name)

  const created = await page.request.post('/api/v1/contracts', { data: { serviceId, spec: spec('1.0.0') } })
  expect(created.status()).toBe(201)
  const contractId = (await created.json()).data.id as string
  const published = await page.request.post(`/api/v1/contracts/${contractId}/versions`, {
    data: { spec: spec('1.1.0', HISTORY_PATH) },
  })
  expect(published.status()).toBe(201)

  // A full page load opens the onboarding wizard again (its dismissal is React state only), so skip it each time.
  await expect(async () => {
    await page.goto(`/contracts?q=${encodeURIComponent(name)}`)
    await page.getByRole('button', { name: 'Skip setup' }).click({ timeout: 5_000 })
    await expect(page.getByRole('link', { name, exact: true })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000 })
  await expect(page.getByText('v2 · 1.1.0', { exact: true })).toBeVisible()

  await page.getByRole('link', { name, exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()

  const timeline = page.getByRole('list', { name: 'Version timeline' })
  await expect(timeline.getByRole('button', { name: /v2/ })).toHaveAttribute('aria-current', 'true')
  await expect(timeline.getByRole('button', { name: /v1/ })).toBeVisible()

  const changes = page.getByRole('list', { name: 'Changes', exact: true })
  await expect(changes.getByText('paths › /entries/{id}/history', { exact: true })).toBeVisible()
  await expect(changes.getByText('1.0.0 → 1.1.0', { exact: true })).toBeVisible()
  const diff = page.getByRole('table', { name: 'Diff v1 to v2' })
  await expect(diff.locator('tr[data-kind="added"]').filter({ hasText: '/entries/{id}/history' })).toHaveCount(1)

  await timeline.getByRole('button', { name: /v1/ }).click()
  await expect(page).toHaveURL(/v=1/)
  await expect(page.getByRole('table', { name: 'Diff empty to v1' })).toBeVisible()
})

// A removed path makes the new version breaking: the list badge, the status filter and the detail page show it.
test('a breaking version shows its badge, its filter and its rule hits', async ({ page }) => {
  const name = uniqueName('ledger-removed')
  const serviceId = await registerService(page, name)

  const created = await page.request.post('/api/v1/contracts', {
    data: { serviceId, spec: spec('1.0.0', HISTORY_PATH) },
  })
  expect(created.status()).toBe(201)
  const contractId = (await created.json()).data.id as string
  const published = await page.request.post(`/api/v1/contracts/${contractId}/versions`, {
    data: { spec: spec('2.0.0') },
  })
  expect(published.status()).toBe(201)
  expect((await published.json()).data.breaking).toBe(true)

  await expect(async () => {
    await page.goto(`/contracts?q=${encodeURIComponent(name)}`)
    await page.getByRole('button', { name: 'Skip setup' }).click({ timeout: 5_000 })
    await expect(page.getByRole('link', { name, exact: true })).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000 })
  const row = page.getByRole('row').filter({ hasText: name })
  await expect(row.getByText('Breaking', { exact: true })).toBeVisible()

  await page.getByRole('radio', { name: 'Breaking', exact: true }).click()
  await expect(page).toHaveURL(/status=breaking/)
  await expect(page.getByRole('link', { name, exact: true })).toBeVisible()
  await page.getByRole('radio', { name: 'Compatible', exact: true }).click()
  await expect(page).toHaveURL(/status=compatible/)
  await expect(page.getByRole('link', { name, exact: true })).not.toBeVisible()
  await page.getByRole('radio', { name: 'All', exact: true }).click()

  await page.getByRole('link', { name, exact: true }).click()
  const breaking = page.getByRole('list', { name: 'Breaking changes' })
  await expect(breaking.getByText('path-removed', { exact: true })).toBeVisible()
  await expect(breaking.getByText('/entries/{id}/history', { exact: true })).toBeVisible()
})
