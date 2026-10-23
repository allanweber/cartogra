// Read-heavy API load through the gateway for one tenant. Setup registers SERVICE_COUNT services
// (perf-svc-N, matching publish-spans.sh) chained by declared dependencies, then every iteration
// hits one of the main read endpoints. Thresholds fail the run on errors or slow p95.
import http from 'k6/http'
import { check, fail } from 'k6'

const BASE_URL = __ENV.GATEWAY_URL || 'http://localhost:8080'
const COOKIE = __ENV.COOKIE || fail('COOKIE is required (see login.sh)')
const SERVICE_COUNT = Number(__ENV.SERVICE_COUNT || 8)

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 40),
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
    checks: ['rate>0.99'],
  },
}

const params = { headers: { Cookie: COOKIE, 'Content-Type': 'application/json' } }

function post(path, body) {
  const res = http.post(`${BASE_URL}${path}`, JSON.stringify(body), params)
  if (res.status !== 201 && res.status !== 200) {
    fail(`setup POST ${path} -> ${res.status} ${res.body}`)
  }
  return res.json('data')
}

export function setup() {
  const ids = []
  for (let i = 0; i < SERVICE_COUNT; i++) {
    ids.push(post('/api/v1/registry/services', { name: `perf-svc-${i}` }).id)
  }
  for (let i = 0; i < ids.length - 1; i++) {
    post('/api/v1/topology/dependencies', {
      sourceServiceId: ids[i],
      targetServiceId: ids[i + 1],
      protocol: 'HTTP',
    })
  }
  return { ids }
}

const reads = [
  () => '/api/v1/topology/graph',
  () => '/api/v1/topology/graph?type=OBSERVED',
  () => '/api/v1/registry/services',
  () => '/api/v1/topology/cycles',
  () => '/api/v1/topology/spofs',
  () => '/api/v1/topology/risks',
  () => '/api/v1/topology/drifts',
  () => '/api/v1/topology/unresolved-span-services',
  (ids) => `/api/v1/topology/blast-radius/${ids[Math.floor(Math.random() * ids.length)]}`,
  (ids) => `/api/v1/registry/services/${ids[Math.floor(Math.random() * ids.length)]}`,
]

export default function ({ ids }) {
  const path = reads[Math.floor(Math.random() * reads.length)](ids)
  const res = http.get(`${BASE_URL}${path}`, { ...params, tags: { name: path.replace(/[0-9a-f-]{36}/, '{id}') } })
  check(res, { 'status is 200': (r) => r.status === 200 })
}
