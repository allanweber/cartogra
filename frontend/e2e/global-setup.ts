import { mkdir } from 'node:fs/promises'
import path from 'node:path'

import { request } from '@playwright/test'
import { Client } from 'pg'

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:3006'
const dbUrl = process.env.E2E_DB_URL ?? 'postgresql://cartogra:cartogra@localhost:5436/cartogra'
const storageStatePath = path.join(import.meta.dirname, '.auth', 'storage-state.json')

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
  await mkdir(path.dirname(storageStatePath), { recursive: true })

  const email = `e2e-${Date.now()}@cartogra.test`
  const password = 'Cartogra-e2e-1!'

  const api = await request.newContext({ baseURL })
  try {
    const register = await api.post('/api/auth/register', {
      data: { orgName: `E2E Tenant ${Date.now()}`, email, password },
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
  } finally {
    await api.dispose()
  }
}
