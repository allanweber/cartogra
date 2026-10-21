import { defineConfig, devices } from '@playwright/test'

// Dedicated port, never the developer's :3006 dev server: reusing that one leaks its session/tenant into the suite.
// The gateway's default CORS origins include it.
const E2E_PORT = 3016
const baseURL = process.env.E2E_BASE_URL ?? `http://localhost:${E2E_PORT}`

export default defineConfig({
  testDir: './e2e',
  // 90s: demo-flow.spec.ts retries its cycle-badge checks against the topology MV's debounced
  // refresh (up to 20s + 10s of that budget alone) on top of the rest of the flow.
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['html', { open: 'never' }], ['github']]
    : [['html', { open: 'never' }], ['list']],
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  use: {
    baseURL,
    storageState: './e2e/.auth/storage-state.json',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite dev --port ${E2E_PORT} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      VITE_API_BASE_URL: process.env.E2E_API_BASE_URL ?? 'http://localhost:8080',
    },
  },
})
