import { spawn } from 'node:child_process'
import { createServer } from 'node:net'
import { dormantCalls, type SeedCall, type SeedData } from './schema.ts'

export function driftCalls(seed: SeedData): SeedCall[] {
  const calls = dormantCalls(seed)
  if (calls.length === 0) throw new Error('seed-data.json has no dormant (undeclared) call')
  return calls
}

export async function setDrift(seed: SeedData, enable: boolean): Promise<SeedCall[]> {
  const calls = driftCalls(seed)
  for (const call of calls) await toggleCall(seed, call, enable)
  return calls
}

async function toggleCall(seed: SeedData, call: SeedCall, enable: boolean): Promise<void> {
  const port = await freePort()
  const forward = spawn(
    'kubectl',
    ['-n', seed.namespace, 'port-forward', `deploy/${call.from}`, `${port}:8080`],
    { stdio: ['ignore', 'pipe', 'inherit'] },
  )
  try {
    await new Promise<void>((resolve, reject) => {
      forward.once('error', reject)
      forward.once('exit', (code) => reject(new Error(`kubectl port-forward exited early (${code})`)))
      forward.stdout.on('data', (chunk: Buffer) => {
        if (chunk.toString().includes('Forwarding from')) resolve()
      })
    })
    const action = enable ? 'enable' : 'disable'
    const response = await fetch(`http://127.0.0.1:${port}/admin/calls/${call.to}/${action}`, { method: 'POST' })
    if (response.status !== 204) throw new Error(`${call.from} admin ${action} failed: HTTP ${response.status}`)
  } finally {
    forward.removeAllListeners('exit')
    forward.kill()
  }
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      server.close(() => (typeof address === 'object' && address ? resolve(address.port) : reject(new Error('no port'))))
    })
  })
}
