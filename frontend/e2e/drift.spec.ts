import { expect, test } from '@playwright/test'

import type { Route } from '@playwright/test'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

interface StubDrift {
  id: string
  type: 'UNDECLARED' | 'MISSING'
  sourceServiceId: string
  targetServiceId: string
  detectedAt: string
  resolvedAt: null
  resolution: null
}

// Real drift needs spans and a time window, so the drift API is stubbed here; the backend path
// (span fixture -> sweep -> drift -> resolve) is covered by DriftControllerIT and `pnpm acme verify`.
test('triage drift from the graph: accept, remove declared and dismiss', async ({ page }) => {
  const source = uniqueName('reporting')
  const target = uniqueName('payments')

  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()
  for (const name of [source, target]) {
    await page.getByRole('button', { name: 'Register service' }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('Service name').fill(name)
    await dialog.getByRole('button', { name: 'Register service' }).click()
    await expect(dialog).not.toBeVisible()
    await expect(page.getByRole('link', { name })).toBeVisible()
  }

  const serviceIds: Record<string, string> = {}
  for (const name of [source, target]) {
    const href = await page.getByRole('link', { name }).getAttribute('href')
    serviceIds[name] = href!.split('/').pop()!
  }

  const drift = (id: string, type: StubDrift['type']): StubDrift => ({
    id,
    type,
    sourceServiceId: serviceIds[source],
    targetServiceId: serviceIds[target],
    detectedAt: new Date().toISOString(),
    resolvedAt: null,
    resolution: null,
  })
  let drifts: StubDrift[] = [drift('11111111-1111-1111-1111-111111111111', 'UNDECLARED')]
  const resolveBodies: { id: string; action: string }[] = []

  const envelope = (data: unknown) => ({ data, traceId: '0'.repeat(32) })
  await page.route('**/api/v1/topology/drifts**', async (route: Route) => {
    const request = route.request()
    const resolveMatch = request.url().match(/drifts\/([^/]+)\/resolve/)
    if (request.method() === 'POST' && resolveMatch) {
      const body = request.postDataJSON() as { action: string }
      resolveBodies.push({ id: resolveMatch[1], action: body.action })
      drifts = drifts.filter((d) => d.id !== resolveMatch[1])
      await route.fulfill({ json: envelope({ id: resolveMatch[1], resolution: 'ACCEPTED' }) })
      return
    }
    await route.fulfill({ json: envelope({ items: drifts, total: drifts.length, limit: 200, offset: 0 }) })
  })

  async function openDriftMode() {
    await page.getByRole('link', { name: 'Catalog', exact: true }).click()
    await expect(page.getByRole('link', { name: source })).toBeVisible()
    await page.getByRole('link', { name: 'Graph', exact: true }).click()
    await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()
    await page.getByRole('radio', { name: 'Drift' }).click()
  }

  await openDriftMode()
  const row = page.getByRole('listitem').filter({ hasText: `${source} → ${target}` })
  await expect(row).toBeVisible()
  await expect(row.getByText('Undeclared')).toBeVisible()

  await row.getByRole('button', { name: 'Accept' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Accept' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(row).not.toBeVisible()
  expect(resolveBodies.at(-1)).toEqual({ id: '11111111-1111-1111-1111-111111111111', action: 'ACCEPT_OBSERVED' })

  drifts = [drift('22222222-2222-2222-2222-222222222222', 'MISSING')]
  await openDriftMode()
  const missingRow = page.getByRole('listitem').filter({ hasText: 'Missing' })
  await expect(missingRow).toBeVisible()
  await missingRow.getByRole('button', { name: 'Remove declared' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Remove declared' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(missingRow).not.toBeVisible()
  expect(resolveBodies.at(-1)).toEqual({ id: '22222222-2222-2222-2222-222222222222', action: 'REMOVE_DECLARED' })

  drifts = [drift('33333333-3333-3333-3333-333333333333', 'UNDECLARED')]
  await openDriftMode()
  const dismissRow = page.getByRole('listitem').filter({ hasText: 'Undeclared' })
  await dismissRow.getByRole('button', { name: 'Dismiss' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Dismiss' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(dismissRow).not.toBeVisible()
  expect(resolveBodies.at(-1)).toEqual({ id: '33333333-3333-3333-3333-333333333333', action: 'DISMISS' })
  await expect(page.getByText(/no drift/i).first()).toBeVisible()
})
