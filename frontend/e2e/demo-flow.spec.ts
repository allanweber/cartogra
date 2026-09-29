import { expect, test } from '@playwright/test'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

test('register services, declare a dependency, and see it on the graph', async ({ page }) => {
  const upstreamService = uniqueName('checkout')
  const downstreamService = uniqueName('payments')
  const thirdService = uniqueName('ledger')

  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()

  for (const name of [upstreamService, downstreamService, thirdService]) {
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
  await expect(editDialog).not.toBeVisible()

  // Close a 3-node cycle: upstream -> downstream already exists (edited above);
  // downstream -> third and third -> upstream complete it, from each service's own page.
  await page.getByRole('button', { name: 'Add dependency' }).click()
  const thirdDependencyDialog = page.getByRole('dialog')
  await thirdDependencyDialog.getByPlaceholder('Search services…').fill(thirdService)
  await thirdDependencyDialog.getByRole('option', { name: thirdService }).click()
  await thirdDependencyDialog.getByRole('button', { name: 'Add dependency' }).click()
  await expect(thirdDependencyDialog).not.toBeVisible()

  await page.getByRole('link', { name: thirdService }).click()
  await expect(page.locator('#main-content').getByRole('heading', { name: thirdService })).toBeVisible()
  await page.getByRole('tab', { name: 'Dependencies' }).click()
  await page.getByRole('button', { name: 'Add dependency' }).click()
  const closingDependencyDialog = page.getByRole('dialog')
  await closingDependencyDialog.getByPlaceholder('Search services…').fill(upstreamService)
  await closingDependencyDialog.getByRole('option', { name: upstreamService }).click()
  await closingDependencyDialog.getByRole('button', { name: 'Add dependency' }).click()
  await expect(closingDependencyDialog).not.toBeVisible()

  // Cycle detection reads dependency_graph_edges, a materialized view that only picks up
  // this closing edge on its own debounced refresh tick (topology.graph-view.refresh-interval,
  // default 5s, but scheduler/DB/CI load can push the real gap well past that) — not on write.
  // Neither the cycles query on this page nor the graph page's has a refetch interval, so a
  // single fixed wait races the refresh instead of actually waiting for it: navigate away and
  // back (forcing a fresh mount / fresh fetch) and retry until the badge appears, up to a
  // generous ceiling, rather than guessing the exact delay.
  //
  // Deliberately NOT page.reload(): OnboardingWizard's dismissal is in-memory React state
  // (never persisted), so a hard reload resurfaces the "Get started" modal and blocks
  // everything behind it — in-app client-side navigation doesn't remount that layout-level
  // component, only the route beneath it, which is exactly the remount the query needs.
  const cycleBadgeMatcher = { name: /1 dependency cycle/i }
  await expect(async () => {
    await page.getByRole('link', { name: 'Service Catalog' }).click()
    await page.getByRole('link', { name: thirdService }).click()
    await expect(page.locator('#main-content').getByRole('heading', { name: thirdService })).toBeVisible()
    await expect(page.getByRole('button', cycleBadgeMatcher)).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000, intervals: [2_000] })

  await page.getByRole('link', { name: 'Graph', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  // The MV was already confirmed refreshed above, so this should be immediate — but retry
  // the same way rather than assume, since a fresh navigation is a fresh query regardless.
  await expect(async () => {
    await expect(page.getByRole('button', cycleBadgeMatcher)).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 10_000, intervals: [2_000] })

  const cycleBadge = page.getByRole('button', cycleBadgeMatcher)
  await cycleBadge.click()
  const popover = page.getByRole('dialog')
  for (const name of [upstreamService, downstreamService, thirdService]) {
    await expect(popover.getByText(name)).toBeVisible()
  }
  await page.keyboard.press('Escape')

  await expect(page.locator('text.graph-node-cycle-badge[opacity="1"]')).toHaveCount(3)

  await page.getByRole('radio', { name: 'Observed' }).click()
  await expect(page.getByText(/observed dependencies aren.t collected yet/i)).toBeVisible()

  await page.getByRole('radio', { name: 'Declared' }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  await page.locator('.graph-node circle').first().click()
  await expect(page.getByRole('tab', { name: 'Blast Radius' })).toBeVisible()

  // Keyboard path: the other node, selected via focus + Enter rather than a click,
  // must reach the same details panel — proves the a11y affordance actually works,
  // not just that an aria-label attribute is present.
  await page.locator('.graph-node').nth(1).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('tab', { name: 'Blast Radius' })).toBeVisible()
})
