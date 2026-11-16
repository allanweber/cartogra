import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { openapiSpec, remoteFor, renderRepo, routesFor, teamSlug } from '../src/repos.ts'
import { loadSeedFile, type SeedService } from '../src/schema.ts'

const seed = loadSeedFile(resolve(import.meta.dirname, '../seed-data.json'))
const asGithub = (s: SeedService): SeedService => ({ ...s, repo: { ...s.repo, provider: 'github' } })
const byName = (n: string): SeedService => seed.services.find((s) => s.name === n)!

describe('seed repos', () => {
  it('puts every repo on Azure DevOps and covers all stacks', () => {
    const providers = new Set(seed.services.map((s) => s.repo.provider))
    const stacks = new Set(seed.services.map((s) => s.repo.stack))
    expect(providers).toEqual(new Set(['azure']))
    expect(stacks).toEqual(new Set(['java', 'node', 'python']))
  })
})

describe('renderRepo', () => {
  it('writes the markers TechStackDetector looks for', () => {
    expect(renderRepo(byName('payments-service')).get('build.gradle.kts')).toContain('spring-boot')
    expect(renderRepo(byName('user-service')).has('package.json')).toBe(true)
    expect(renderRepo(byName('fx-service')).has('requirements.txt')).toBe(true)
  })

  it('writes CODEOWNERS only for owned services, scoped to the org on GitHub', () => {
    expect(renderRepo(asGithub(byName('payments-service')), { githubOrg: 'acme' }).get('CODEOWNERS')).toBe('* @acme/payments\n')
    expect(renderRepo(byName('billing-service'), { githubOrg: 'acme' }).get('CODEOWNERS')).toBe('* @payments\n')
    expect(renderRepo(byName('legacy-batch')).has('CODEOWNERS')).toBe(false)
  })

  it('emits a valid-looking openapi with the service routes', () => {
    const yaml = renderRepo(byName('ledger-service')).get('openapi.yaml')!
    expect(yaml).toContain('openapi: "3.0.3"')
    for (const r of routesFor(byName('ledger-service'))) expect(yaml).toContain(`"${r.path}"`)
  })

  it('declares the {id} path parameter so the spec passes OpenAPI validation', () => {
    const yaml = renderRepo(byName('card-service')).get('openapi.yaml')!
    expect(yaml).toContain('"/cards/{id}":\n    parameters:\n      - name: id\n        in: path\n        required: true')
  })

  it('pushes the latest contract version, which adds a history path and bumps info.version', () => {
    const ledger = byName('ledger-service')
    expect(ledger.contractVersions).toBe(2)
    expect(renderRepo(ledger).get('openapi.yaml')).toBe(openapiSpec(ledger, 2))
    expect(openapiSpec(ledger, 1)).toContain('version: "1.0.0"')
    expect(openapiSpec(ledger, 1)).not.toContain('/history')
    expect(openapiSpec(ledger, 2)).toContain('version: "1.1.0"')
    expect(openapiSpec(ledger, 2)).toContain('"/ledgers/{id}/history":')
  })

  it('is deterministic', () => {
    expect([...renderRepo(byName('card-service'))]).toEqual([...renderRepo(byName('card-service'))])
  })

  it('never uses a latest tag in a Dockerfile', () => {
    for (const s of seed.services) expect(renderRepo(s).get('Dockerfile')).not.toContain(':latest')
  })
})

describe('remoteFor', () => {
  it('builds provider urls from env-style options', () => {
    expect(remoteFor(asGithub(byName('payments-service')), { githubOrg: 'acme' }).url).toBe('https://github.com/acme/payments-service.git')
    expect(remoteFor(byName('billing-service'), { azureOrg: 'https://dev.azure.com/acme/', azureProject: 'Fin' }).url).toBe(
      'https://dev.azure.com/acme/Fin/_git/billing-service',
    )
  })

  it('requires the org settings', () => {
    expect(() => remoteFor(asGithub(byName('payments-service')), {})).toThrow(/ACME_GITHUB_ORG/)
    expect(() => remoteFor(byName('billing-service'), {})).toThrow(/ACME_AZURE_ORG/)
  })

  it('slugs team names', () => {
    expect(teamSlug('Payments')).toBe('payments')
  })
})
