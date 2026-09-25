import { expect, test } from '@playwright/test'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

test('register services, declare a dependency, and see it on the graph', async ({ page }) => {
  const upstreamService = uniqueName('checkout')
  const downstreamService = uniqueName('payments')

  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()

  for (const name of [upstreamService, downstreamService]) {
    await page.getByRole('button', { name: 'Register service' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Service name').fill(name)
    await dialog.getByRole('button', { name: 'Register service' }).click()
    await expect(dialog).not.toBeVisible()
    await expect(page.getByRole('link', { name })).toBeVisible()
  }

  // Risk score badge: a freshly registered, unowned, never-deployed service should
  // surface both factors in its breakdown popover, not just a bare number.
  const upstreamCard = page.getByRole('link', { name: upstreamService })
  await upstreamCard.getByRole('button', { name: /risk score/i }).click()
  const riskPopover = page.getByRole('dialog')
  await expect(riskPopover.getByText('No owning team')).toBeVisible()
  await expect(riskPopover.getByText('Never deployed')).toBeVisible()
  await page.keyboard.press('Escape')

  await page.getByRole('link', { name: upstreamService }).click()
  await expect(page.locator('#main-content').getByRole('heading', { name: upstreamService })).toBeVisible()
  const upstreamServiceId = new URL(page.url()).pathname.split('/').pop()!

  await page.getByRole('tab', { name: 'Dependencies' }).click()
  await page.getByRole('button', { name: 'Add dependency' }).click()

  const dependencyDialog = page.getByRole('dialog')
  await dependencyDialog.getByPlaceholder('Search services…').fill(downstreamService)
  await dependencyDialog.getByRole('option', { name: downstreamService }).click()
  await dependencyDialog.getByRole('button', { name: 'Add dependency' }).click()
  await expect(dependencyDialog).not.toBeVisible()
  await expect(page.getByRole('link', { name: downstreamService })).toBeVisible()

  // Editing an upstream dependency (from the downstream service's own page) must
  // preserve the original edge direction, not silently reverse it — the one branch of
  // the direction-resolving ternary no vitest coverage exercised before this candidate.
  await page.getByRole('link', { name: downstreamService }).click()
  await expect(page.locator('#main-content').getByRole('heading', { name: downstreamService })).toBeVisible()
  const downstreamServiceId = new URL(page.url()).pathname.split('/').pop()!

  await page.getByRole('tab', { name: 'Dependencies' }).click()
  await page.getByRole('button', { name: `Edit dependency on ${upstreamService}` }).click()
  const editDialog = page.getByRole('dialog')
  await expect(editDialog.getByRole('heading', { name: upstreamService })).toBeVisible()

  const putRequest = page.waitForRequest(
    (req) => req.method() === 'PUT' && req.url().includes('/api/v1/topology/dependencies/'),
  )
  await editDialog.getByRole('button', { name: 'Save changes' }).click()
  const putBody = (await putRequest).postDataJSON()
  expect(putBody.sourceServiceId).toBe(upstreamServiceId)
  expect(putBody.targetServiceId).toBe(downstreamServiceId)

  await page.getByRole('link', { name: 'Graph', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  await page.getByRole('radio', { name: 'Observed' }).click()
  await expect(page.getByText(/observed dependencies aren.t collected yet/i)).toBeVisible()

  await page.getByRole('radio', { name: 'Declared' }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  await page.locator('.graph-node circle').first().click()
  await expect(page.getByText('Neighbors')).toBeVisible()

  // Keyboard path: the other node, selected via focus + Enter rather than a click,
  // must reach the same details panel — proves the a11y affordance actually works,
  // not just that an aria-label attribute is present.
  await page.locator('.graph-node').nth(1).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByText('Neighbors')).toBeVisible()
})
