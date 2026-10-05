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

  // Blast radius must terminate against a live cyclic graph, not hang or error — the
  // recursive CTE's visited-path guard is what prevents an infinite walk here. Selecting a
  // cycle member (thirdService) should resolve promptly and surface both other members,
  // which only happens if the traversal actually walked the cycle instead of erroring out.
  // Click the hit-target circle, not the enclosing <g> — Playwright's click-point
  // computation on an SVG <g> with a transform attribute lands on the raw canvas instead
  // of the node (same reason the earlier `.graph-node circle` click above targets a circle).
  const thirdNode = page.getByRole('button', { name: new RegExp(`^${thirdService},`) })
  await thirdNode.locator('circle.graph-node-hit').click()
  // Both other cycle members appear in both directions here (a 3-cycle walked either way
  // reaches the same two other nodes), hence .first() rather than a single unique match.
  const cycleBlastRadiusPanel = page.getByRole('tabpanel')
  await expect(cycleBlastRadiusPanel.getByText(upstreamService).first()).toBeVisible()
  await expect(cycleBlastRadiusPanel.getByText(downstreamService).first()).toBeVisible()
  await expect(cycleBlastRadiusPanel.getByText(/failed to load/i)).not.toBeVisible()

  // SPOF: thirdService already has one dependent (downstreamService, from the cycle above).
  // Register four more services, each declaring a dependency on thirdService, to cross the
  // default fan-in threshold of 5.
  const spofDependents = [uniqueName('svc-a'), uniqueName('svc-b'), uniqueName('svc-c'), uniqueName('svc-d')]
  await page.getByRole('link', { name: 'Catalog', exact: true }).click()
  for (const name of spofDependents) {
    await page.getByRole('button', { name: 'Register service' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Service name').fill(name)
    await dialog.getByRole('button', { name: 'Register service' }).click()
    await expect(dialog).not.toBeVisible()
    await expect(page.getByRole('link', { name })).toBeVisible()
  }
  for (const name of spofDependents) {
    await page.getByRole('link', { name }).click()
    await expect(page.locator('#main-content').getByRole('heading', { name })).toBeVisible()
    await page.getByRole('tab', { name: 'Dependencies' }).click()
    await page.getByRole('button', { name: 'Add dependency' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByPlaceholder('Search services…').fill(thirdService)
    await dialog.getByRole('option', { name: thirdService }).click()
    await dialog.getByRole('button', { name: 'Add dependency' }).click()
    await expect(dialog).not.toBeVisible()
    await page.getByRole('link', { name: 'Service Catalog' }).click()
  }

  // Same debounced-MV-refresh reasoning as the cycle badge above: navigate away and back,
  // retrying until the SPOF badge appears rather than guessing the refresh delay.
  const spofBadgeMatcher = { name: /1 single point of failure/i }
  await expect(async () => {
    await page.getByRole('link', { name: 'Catalog', exact: true }).click()
    await page.getByRole('link', { name: thirdService }).click()
    await expect(page.locator('#main-content').getByRole('heading', { name: thirdService })).toBeVisible()
    await expect(page.getByRole('button', spofBadgeMatcher)).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000, intervals: [2_000] })

  const spofsResponsePromise = page.waitForResponse(
    (res) => res.url().includes('/api/v1/topology/spofs') && res.status() === 200,
  )
  await page.getByRole('link', { name: 'Graph', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()
  await expect(async () => {
    await expect(page.getByRole('button', spofBadgeMatcher)).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 10_000, intervals: [2_000] })

  // threshold/rationale travel in the GET /v1/topology/spofs payload — assert the wire
  // response directly, then the popover below surfaces them.
  const spofsBody = await (await spofsResponsePromise).json()
  expect(spofsBody.data.threshold).toBeGreaterThan(0)
  expect(spofsBody.data.rationale).toContain(String(spofsBody.data.threshold))

  const spofBadge = page.getByRole('button', spofBadgeMatcher)
  await spofBadge.click()
  const spofPopover = page.getByRole('dialog')
  await expect(spofPopover.getByText(new RegExp(`${thirdService} —`))).toBeVisible()
  await expect(spofPopover.getByText(new RegExp(`Flagged at ${spofsBody.data.threshold}\\+ dependents`))).toBeVisible()
  await page.keyboard.press('Escape')

  await expect(page.locator('text.graph-node-spof-badge[opacity="1"]')).toHaveCount(1)

  // Risks page: both the cycle and the SPOF just created should surface here too, from the
  // same underlying state — no separate seed needed.
  await page.getByRole('link', { name: 'Risks', exact: true }).click()
  await expect(async () => {
    await expect(page.getByText(/circular dependency among 3 services/i)).toBeVisible({ timeout: 2_000 })
    await expect(
      page.getByText(new RegExp(`single point of failure: ${thirdService}`, 'i')),
    ).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 10_000, intervals: [2_000] })

  // Back to the graph for the remaining edge-mode/blast-radius checks below.
  await page.getByRole('link', { name: 'Graph', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  await page.getByRole('radio', { name: 'Observed' }).click()
  await expect(page.getByText(/no observed dependencies yet/i)).toBeVisible()

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

  // Depth-cap truncation: a chain longer than the default depth (3) must surface the
  // "Truncated at N hops" banner, not silently stop at whatever the CTE happened to return.
  // The tenant plan caps services at 10, so the chain reuses the four SPOF dependents
  // (svc-a -> svc-b -> svc-c -> svc-d) and adds one new head: chain-head -> svc-a -> ... -> svc-d
  // puts svc-d 4 hops from the head, one past the default depth.
  const chainHeadName = uniqueName('chain-head')
  const chainServices = [chainHeadName, ...spofDependents]
  await page.getByRole('link', { name: 'Catalog', exact: true }).click()
  await page.getByRole('button', { name: 'Register service' }).click()
  const headDialog = page.getByRole('dialog')
  await headDialog.getByLabel('Service name').fill(chainHeadName)
  await headDialog.getByRole('button', { name: 'Register service' }).click()
  await expect(headDialog).not.toBeVisible()
  await expect(page.getByRole('link', { name: chainHeadName })).toBeVisible()
  for (let i = 0; i < chainServices.length - 1; i++) {
    await page.getByRole('link', { name: chainServices[i] }).click()
    await expect(page.locator('#main-content').getByRole('heading', { name: chainServices[i] })).toBeVisible()
    await page.getByRole('tab', { name: 'Dependencies' }).click()
    await page.getByRole('button', { name: 'Add dependency' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByPlaceholder('Search services…').fill(chainServices[i + 1])
    await dialog.getByRole('option', { name: chainServices[i + 1] }).click()
    await dialog.getByRole('button', { name: 'Add dependency' }).click()
    await expect(dialog).not.toBeVisible()
    await page.getByRole('link', { name: 'Service Catalog' }).click()
  }

  // Same debounced-MV-refresh reasoning as the cycle/SPOF badges above: navigate away and
  // back, retrying until the truncation banner appears rather than guessing the delay.
  const chainHead = page.getByRole('button', { name: new RegExp(`^${chainServices[0]},`) })
  await expect(async () => {
    await page.getByRole('link', { name: 'Catalog', exact: true }).click()
    await page.getByRole('link', { name: 'Graph', exact: true }).click()
    await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()
    await chainHead.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByText(/Truncated at \d+ hops/)).toBeVisible({ timeout: 2_000 })
  }).toPass({ timeout: 20_000, intervals: [2_000] })
})
