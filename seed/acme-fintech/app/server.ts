import { createServer } from 'node:http'
import { CallScheduler, type CallConfig } from './calls.ts'
import { startTracing } from './tracing.ts'

const serviceName = requireEnv('SERVICE_NAME')
const namespace = process.env.ACME_NAMESPACE ?? 'acme-fintech'
const port = Number(process.env.PORT ?? 8080)
const calls = JSON.parse(process.env.ACME_CALLS ?? '[]') as CallConfig[]
const produces = JSON.parse(process.env.ACME_PRODUCES ?? '[]') as string[]
const consumes = JSON.parse(process.env.ACME_CONSUMES ?? '[]') as string[]
const databases = JSON.parse(process.env.ACME_DB_CALLS ?? '[]') as string[]
const intervalMs = Number(process.env.ACME_CALL_INTERVAL_MS ?? 5000)

const tracing = startTracing({
  serviceName,
  tenantId: requireEnv('ACME_TENANT_ID'),
  namespace,
  otlpEndpoint: requireEnv('ACME_OTLP_ENDPOINT'),
})

const scheduler = new CallScheduler({
  calls,
  intervalMs,
  invoke: (target) => {
    const address = `${target}.${namespace}.svc.cluster.local`
    return tracing.clientCall(target, address, async (headers) => {
      const response = await fetch(`http://${address}:${port}/work`, { headers, signal: AbortSignal.timeout(3000) })
      if (!response.ok) throw new Error(`${target} responded ${response.status}`)
    })
  },
  onError: (target, error) => console.error(`${serviceName} -> ${target} failed: ${String(error)}`),
})

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const admin = /^\/admin\/calls\/([a-z0-9-]+)\/(enable|disable)$/.exec(url.pathname)

  if (req.method === 'GET' && url.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"status":"UP"}')
  } else if (req.method === 'GET' && url.pathname === '/work') {
    tracing.serverSpan(req.headers, () => res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"ok":true}'))
  } else if (req.method === 'POST' && admin) {
    const changed = scheduler.setEnabled(admin[1], admin[2] === 'enable')
    res.writeHead(changed ? 204 : 404).end()
  } else {
    res.writeHead(404).end()
  }
})

const asyncTimer = setInterval(() => {
  for (const destination of produces) tracing.producerSpan(destination)
  for (const destination of consumes) tracing.consumerSpan(destination)
  for (const database of databases) tracing.dbSpan(database)
}, intervalMs)

server.listen(port, () => {
  console.log(
    `${serviceName} listening on ${port}, ${calls.length} downstream call(s), ` +
      `${produces.length} produced / ${consumes.length} consumed topic(s), ${databases.length} database(s) configured`,
  )
  scheduler.start()
})

process.on('SIGTERM', () => {
  clearInterval(asyncTimer)
  scheduler.stop()
  server.close()
  void tracing.shutdown().finally(() => process.exit(0))
})

function requireEnv(name: string): string {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}
