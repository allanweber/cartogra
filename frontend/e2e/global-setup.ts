import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

import { request } from '@playwright/test'
import { Client } from 'pg'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:3006'
const dbUrl = process.env.E2E_DB_URL ?? 'postgresql://cartogra:cartogra@localhost:5436/cartogra'
const storageStatePath = path.join(import.meta.dirname, '.auth', 'storage-state.json')
export const runInfoPath = path.join(import.meta.dirname, '.auth', 'run.json')

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])

// The suite registers a tenant plus services and edges per run and reads verification tokens
// straight from the database. Pointing it at anything but a local stack would silently create
// accounts there, so remote targets must be opted into explicitly.
function assertLocalTargets() {
  if (process.env.E2E_ALLOW_REMOTE === '1') return
  const hosts = [new URL(baseURL).hostname, new URL(dbUrl).hostname]
  const remote = hosts.filter((host) => !LOCAL_HOSTS.has(host))
  if (remote.length > 0) {
    throw new Error(
      `Refusing to run e2e against non-local host(s) ${remote.join(', ')} — set E2E_ALLOW_REMOTE=1 to override.`,
    )
  }
}

async function fetchVerificationToken(email: string): Promise<string> {
  const client = new Client({ connectionString: dbUrl })
  await client.connect()
  try {
    for (let attempt = 0; attempt < 20; attempt++) {
      const result = await client.query<{ email_verification_token: string | null }>(
        'SELECT email_verification_token FROM registry.users WHERE email = $1 ORDER BY created_at DESC LIMIT 1',
        [email],
      )
      const token = result.rows[0]?.email_verification_token
      if (token) return token
      await new Promise((resolve) => setTimeout(resolve, 250))
    }
    throw new Error(`Verification token for ${email} never appeared in the database`)
  } finally {
    await client.end()
  }
}

export default async function globalSetup() {
  assertLocalTargets()
  await mkdir(path.dirname(storageStatePath), { recursive: true })

  const runId = Date.now()
  const email = `e2e-${runId}@cartogra.test`
  const orgName = `E2E Tenant ${runId}`
  const password = 'Cartogra-e2e-1!'

  const api = await request.newContext({ baseURL })
  try {
    const register = await api.post('/api/auth/register', {
      data: { orgName, email, password },
    })
    if (!register.ok()) {
      throw new Error(`Registration failed: ${register.status()} ${await register.text()}`)
    }

    const token = await fetchVerificationToken(email)

    const verify = await api.post('/api/auth/verify', { data: { email, token } })
    if (!verify.ok()) {
      throw new Error(`Verification failed: ${verify.status()} ${await verify.text()}`)
    }

    const login = await api.post('/api/auth/login', { data: { email, password } })
    if (!login.ok()) {
      throw new Error(`Login failed: ${login.status()} ${await login.text()}`)
    }

    await api.storageState({ path: storageStatePath })
    await writeFile(runInfoPath, JSON.stringify({ orgName, email }))
  } finally {
    await api.dispose()
  }
}
