import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { renderManifests } from '../src/manifests.ts'
import { loadSeedFile } from '../src/schema.ts'

const seed = loadSeedFile(resolve(import.meta.dirname, '../seed-data.json'))
const options = {
  tenantId: '0e1cd186-9768-4b5c-9bab-b5933ebf25df',
  otlpEndpoint: 'http://172.18.0.1:4318',
  image: 'acme-fintech-service:1.0.0',
  version: '1.0.0',
}
const yaml = renderManifests(seed, options)
const count = (needle: string) => yaml.split(needle).length - 1

describe('renderManifests', () => {
  it('labels the namespace with the tenant id for discovery', () => {
    expect(yaml).toContain(`cartogra.io/tenant-id: "${options.tenantId}"`)
    expect(yaml).toContain('name: acme-fintech')
  })

  it('emits 21 deployments and 21 services', () => {
    expect(count('kind: Deployment')).toBe(21)
    expect(count('kind: Service\n')).toBe(21)
  })

  it('follows the infra rules on every container', () => {
    for (const needle of ['startupProbe:', 'livenessProbe:', 'readinessProbe:', 'readOnlyRootFilesystem: true', 'allowPrivilegeEscalation: false', 'drop: [ALL]', 'runAsNonRoot: true', 'runAsUser: 1000', 'limits:', 'requests:']) {
      expect(count(needle)).toBe(21)
    }
    expect(yaml).not.toContain(':latest')
    expect(yaml).not.toContain('privileged: true')
  })

  it('passes the tenant, endpoint and per-service calls as env', () => {
    expect(count(`value: "${options.tenantId}"`)).toBe(21)
    expect(yaml).toContain('ACME_OTLP_ENDPOINT')
    expect(yaml).toContain(String.raw`{\"to\":\"payments-service\",\"enabled\":false}`)
    expect(yaml).toContain(String.raw`{\"to\":\"stripe-gateway\",\"enabled\":true}`)
  })

  it('passes produced and consumed topics and databases as env', () => {
    const env = (service: string, name: string) => {
      const block = yaml.split('---\n').find((d) => d.includes('kind: Deployment') && d.includes(`name: ${service}\n`))!
      return JSON.parse(JSON.parse(new RegExp(`name: ${name}\\n\\s+value: (.+)`).exec(block)![1])) as unknown
    }
    expect(env('payments-service', 'ACME_PRODUCES')).toEqual(['payments.completed'])
    expect(env('notification-service', 'ACME_CONSUMES')).toEqual(['payments.completed'])
    expect(env('loyalty-service', 'ACME_CONSUMES')).toEqual(['payments.completed'])
    expect(env('ledger-service', 'ACME_DB_CALLS')).toEqual(['ledger-db'])
    expect(env('payments-service', 'ACME_CALLS')).not.toContainEqual({ to: 'loyalty-service', enabled: true })
  })

  it('rejects a bad tenant id, missing endpoint and latest tags', () => {
    expect(() => renderManifests(seed, { ...options, tenantId: 'nope' })).toThrow(/UUID/)
    expect(() => renderManifests(seed, { ...options, otlpEndpoint: '' })).toThrow(/OTLP/)
    expect(() => renderManifests(seed, { ...options, image: 'acme:latest' })).toThrow(/tag/)
  })
})
