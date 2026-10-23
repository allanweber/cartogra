import { expect, test } from '@playwright/test'

import type { Page, Route } from '@playwright/test'

function uniqueName(prefix: string) {
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

interface StubUnresolved {
  id: string
  name: string
  firstSeenAt: string
  lastSeenAt: string
  seenCount: number
  resolvedAt: null
  resolution: null
}

async function registerService(page: Page, name: string) {
  await page.getByRole('button', { name: 'Register service' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Service name').fill(name)
  await dialog.getByRole('button', { name: 'Register service' }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByRole('link', { name })).toBeVisible()
}

// Real unresolved rows need spans, so the list API is stubbed; registry create is real. The backend
// path (span -> unresolved row -> dismiss / registration clears it) is covered by
// UnresolvedSpanServiceControllerIT, JdbcUnresolvedSpanServiceRepositoryIT and `pnpm acme verify`.
test('triage unresolved span services from the graph: dismiss and create service', async ({ page }) => {
  const anchor = uniqueName('anchor')
  const external = uniqueName('stripe-gateway')
  const noise = uniqueName('legacy-ldap')

  await page.goto('/catalog')
  await page.getByRole('button', { name: 'Skip setup' }).click()
  await registerService(page, anchor)

  const now = new Date().toISOString()
  let rows: StubUnresolved[] = [
    { id: '11111111-1111-1111-1111-111111111111', name: external, firstSeenAt: now, lastSeenAt: now, seenCount: 412, resolvedAt: null, resolution: null },
    { id: '22222222-2222-2222-2222-222222222222', name: noise, firstSeenAt: now, lastSeenAt: now, seenCount: 37, resolvedAt: null, resolution: null },
  ]
  const dismissed: string[] = []

  const envelope = (data: unknown) => ({ data, traceId: '0'.repeat(32) })
  await page.route('**/api/v1/topology/unresolved-span-services**', async (route: Route) => {
    const request = route.request()
    const dismissMatch = request.url().match(/unresolved-span-services\/([^/]+)\/dismiss/)
    if (request.method() === 'POST' && dismissMatch) {
      dismissed.push(dismissMatch[1])
      rows = rows.filter((r) => r.id !== dismissMatch[1])
      await route.fulfill({ json: envelope({ id: dismissMatch[1], resolution: 'DISMISSED' }) })
      return
    }
    await route.fulfill({ json: envelope({ items: rows, total: rows.length, limit: 20, offset: 0 }) })
  })
  // Stands in for Topology's service.registered handler, which clears the row server-side.
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/registry/services')) {
      const name = (request.postDataJSON() as { name: string }).name
      rows = rows.filter((r) => r.name !== name)
    }
  })

  await page.getByRole('link', { name: 'Graph', exact: true }).click()
  await expect(page.getByRole('group', { name: 'Service dependency graph' })).toBeVisible()

  const list = page.getByRole('list', { name: 'Unresolved services' })
  const noiseRow = list.getByRole('listitem').filter({ hasText: noise })
  await expect(noiseRow).toBeVisible()
  await noiseRow.getByRole('button', { name: 'Dismiss' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Dismiss' }).click()
  await expect(page.getByRole('dialog')).not.toBeVisible()
  await expect(noiseRow).not.toBeVisible()
  expect(dismissed).toEqual(['22222222-2222-2222-2222-222222222222'])

  const externalRow = list.getByRole('listitem').filter({ hasText: external })
  await externalRow.getByRole('button', { name: 'Create service' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Service name')).toHaveValue(external)
  await dialog.getByRole('button', { name: 'Register service' }).click()
  await expect(dialog).not.toBeVisible()
  await expect(externalRow).not.toBeVisible()
  await expect(page.getByRole('heading', { name: 'Unresolved services (0)' })).toBeVisible()

  await page.getByRole('link', { name: 'Catalog', exact: true }).click()
  await expect(page.getByRole('link', { name: external })).toBeVisible()
})
