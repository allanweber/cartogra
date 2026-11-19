import { expect, type Page } from '@playwright/test'

/** Waits until the force simulation of the dependency graph stops, so a node click lands on a node that does not move. */
export async function waitForGraphSettled(page: Page, timeout = 15_000) {
  await expect(page.getByRole('group', { name: /^Service dependency graph/ })).toHaveAttribute(
    'data-settled',
    'true',
    { timeout },
  )
}
