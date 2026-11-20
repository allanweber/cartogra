import { expect, test } from '@playwright/test'
import type { Page, Response } from '@playwright/test'

const CUSTOM_PATH = 'src/main/resources/openapi.yml'

function isGet(path: string) {
  return (r: Response) =>
    r.ok() && r.request().method() === 'GET' && new URL(r.url()).pathname === `/api${path}`
}

async function itemCount(response: Response): Promise<number> {
  const body = (await response.json()) as { data: { items: unknown[] } }
  return body.data.items.length
}

/**
 * A full page load opens the onboarding wizard again when the tenant has no connection and no cluster (its
 * dismissal is React state only). Wait for the two queries that the wizard reads. Then close the wizard only when
 * it will open, so no dialog overlay blocks a later click.
 */
async function openScmConfiguration(page: Page) {
  const connections = page.waitForResponse(isGet('/v1/ingestion/scm-connections'))
  const clusters = page.waitForResponse(isGet('/v1/ingestion/k8s/clusters'))
  await page.goto('/settings/scm')
  const [connectionsRes, clustersRes] = await Promise.all([connections, clusters])
  if ((await itemCount(connectionsRes)) === 0 && (await itemCount(clustersRes)) === 0) {
    await page.getByRole('button', { name: 'Skip setup' }).click()
  }
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('Spec discovery')).toBeVisible()
}

test('an admin adds a custom spec path, sees it after a reload, then removes it', async ({ page }) => {
  await openScmConfiguration(page)

  const defaults = page.getByRole('list', { name: 'Default spec paths' })
  await expect(defaults.getByRole('listitem')).toHaveCount(5)

  await page.getByLabel('Custom spec path').fill(CUSTOM_PATH)
  await page.getByRole('button', { name: 'Add path' }).click()
  const custom = page.getByRole('list', { name: 'Custom spec paths' })
  await expect(custom.getByText(CUSTOM_PATH)).toBeVisible()

  await openScmConfiguration(page)
  await expect(page.getByRole('list', { name: 'Custom spec paths' }).getByText(CUSTOM_PATH)).toBeVisible()

  await page.getByRole('button', { name: `Remove ${CUSTOM_PATH}` }).click()
  await expect(page.getByText('No custom paths.')).toBeVisible()
})
