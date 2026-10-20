import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { HttpCartograApi } from './api.ts'
import { setDrift } from './drift.ts'
import { loadSeed } from './load.ts'
import { renderManifests } from './manifests.ts'
import { pushRepos } from './repos.ts'
import { declaredCalls, declaredCycles, loadSeedFile, unownedServices } from './schema.ts'
import { verify } from './verify.ts'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const VERSION = '1.0.0'

async function main(): Promise<number> {
  const [command, arg, ...flags] = process.argv.slice(2)
  const seed = loadSeedFile(resolve(root, process.env.ACME_SEED_FILE ?? 'seed-data.json'))

  switch (command) {
    case 'validate':
      console.log(
        `${seed.services.length} services, ${seed.teams.length} teams, ${unownedServices(seed).length} orphans, ` +
          `${declaredCycles(seed).length} cycles, ${declaredCalls(seed).length} declared calls`,
      )
      return 0

    case 'manifests': {
      const out = resolve(root, process.env.ACME_MANIFEST_OUT ?? 'k8s/acme-fintech.yaml')
      const yaml = renderManifests(seed, {
        tenantId: env('ACME_TENANT_ID'),
        otlpEndpoint: env('ACME_OTLP_ENDPOINT'),
        image: process.env.ACME_IMAGE ?? `acme-fintech-service:${VERSION}`,
        version: VERSION,
      })
      mkdirSync(dirname(out), { recursive: true })
      writeFileSync(out, yaml)
      console.log(`wrote ${out}\nkubectl apply -f ${out}`)
      return 0
    }

    case 'repos': {
      const summary = pushRepos(
        seed,
        { githubOrg: process.env.ACME_GITHUB_ORG, azureOrg: process.env.ACME_AZURE_ORG, azureProject: process.env.ACME_AZURE_PROJECT },
        console.log,
      )
      console.log(`${summary.pushed.length} pushed, ${summary.unchanged.length} unchanged`)
      return 0
    }

    case 'load':
    case 'verify': {
      const api = await authenticated()
      if (command === 'load') {
        await loadSeed(api, seed, { log: console.log })
        return 0
      }
      const withDrift = flags.includes('--with-drift') || arg === '--with-drift'
      const checks = await verify(api, seed, {
        log: console.log,
        skipObserved: [arg, ...flags].includes('--no-spans'),
        withDrift: withDrift ? { enable: () => setDrift(seed, true).then(() => {}), disable: () => setDrift(seed, false).then(() => {}) } : undefined,
      })
      for (const c of checks) console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.detail ? `  (${c.detail})` : ''}`)
      return checks.every((c) => c.ok) ? 0 : 1
    }

    case 'drift': {
      if (arg !== 'on' && arg !== 'off') throw new Error('usage: drift on|off')
      for (const call of await setDrift(seed, arg === 'on')) console.log(`${call.from} -> ${call.to}: ${arg}`)
      return 0
    }

    default:
      console.error('usage: acme <validate|manifests|repos|load|verify [--with-drift] [--no-spans]|drift on|off>')
      return 2
  }
}

async function authenticated(): Promise<HttpCartograApi> {
  const api = new HttpCartograApi(process.env.ACME_API_URL ?? 'http://localhost:8080')
  await api.login(env('ACME_EMAIL'), env('ACME_PASSWORD'))
  const tenant = await api.tenantId()
  if (tenant !== env('ACME_TENANT_ID')) {
    throw new Error(`logged-in tenant ${tenant} does not match ACME_TENANT_ID ${env('ACME_TENANT_ID')}`)
  }
  return api
}

function env(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

main().then(
  (code) => process.exit(code),
  (error: unknown) => {
    console.error(error instanceof Error ? error.message : error)
    process.exit(1)
  },
)
