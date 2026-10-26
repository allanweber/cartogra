import { callKind, consumedDestinations, enabledCallsFrom, type SeedData, type SeedService } from './schema.ts'

export interface ManifestOptions {
  tenantId: string
  otlpEndpoint: string
  image: string
  version: string
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function renderManifests(seed: SeedData, options: ManifestOptions): string {
  if (!UUID.test(options.tenantId)) throw new Error('ACME_TENANT_ID must be a UUID')
  if (!options.otlpEndpoint) throw new Error('ACME_OTLP_ENDPOINT is required')
  if (options.image.endsWith(':latest') || !options.image.includes(':')) {
    throw new Error('image must carry an explicit, non-latest tag')
  }
  const documents = [namespace(seed, options), ...seed.services.flatMap((s) => [deployment(seed, s, options), service(seed, s, options)])]
  return documents.join('---\n')
}

function namespace(seed: SeedData, options: ManifestOptions): string {
  return `apiVersion: v1
kind: Namespace
metadata:
  name: ${seed.namespace}
  labels:
    app.kubernetes.io/managed-by: acme-fintech-seed
    cartogra.io/tenant-id: "${options.tenantId}"
`
}

function labels(s: SeedService, options: ManifestOptions, indent: number): string {
  const pad = ' '.repeat(indent)
  const team = s.team === null ? 'unowned' : s.team.toLowerCase()
  return [`app: ${s.name}`, `version: "${options.version}"`, 'environment: acme', `team: ${team}`]
    .map((line) => `${pad}${line}`)
    .join('\n')
}

function deployment(seed: SeedData, s: SeedService, options: ManifestOptions): string {
  const enabled = enabledCallsFrom(seed, s.name)
  const calls = enabled.filter((c) => callKind(c) === 'http').map((c) => ({ to: c.to, enabled: true }))
  for (const dormant of seed.calls.filter((c) => c.from === s.name && !c.enabled && callKind(c) === 'http')) {
    calls.push({ to: dormant.to, enabled: false })
  }
  const produces = [...new Set(enabled.filter((c) => callKind(c) === 'messaging').map((c) => c.destination!))]
  const consumes = consumedDestinations(seed, s.name)
  const databases = enabled.filter((c) => callKind(c) === 'db').map((c) => c.to)
  const probe = (name: string, extra: string) => `          ${name}:
            httpGet:
              path: /health
              port: http
${extra}`
  return `apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${s.name}
  namespace: ${seed.namespace}
  labels:
${labels(s, options, 4)}
spec:
  replicas: 1
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels:
      app: ${s.name}
  template:
    metadata:
      labels:
${labels(s, options, 8)}
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
        - name: ${s.name}
          image: ${options.image}
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: SERVICE_NAME
              value: ${s.name}
            - name: ACME_NAMESPACE
              value: ${seed.namespace}
            - name: ACME_TENANT_ID
              value: "${options.tenantId}"
            - name: ACME_OTLP_ENDPOINT
              value: ${JSON.stringify(options.otlpEndpoint)}
            - name: ACME_CALLS
              value: ${JSON.stringify(JSON.stringify(calls))}
            - name: ACME_PRODUCES
              value: ${JSON.stringify(JSON.stringify(produces))}
            - name: ACME_CONSUMES
              value: ${JSON.stringify(JSON.stringify(consumes))}
            - name: ACME_DB_CALLS
              value: ${JSON.stringify(JSON.stringify(databases))}
          resources:
            requests:
              cpu: 25m
              memory: 96Mi
            limits:
              cpu: 50m
              memory: 96Mi
${probe('startupProbe', '            failureThreshold: 30\n            periodSeconds: 2')}
${probe('livenessProbe', '            periodSeconds: 10')}
${probe('readinessProbe', '            periodSeconds: 5')}
          securityContext:
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
            capabilities:
              drop: [ALL]
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
`
}

function service(seed: SeedData, s: SeedService, options: ManifestOptions): string {
  return `apiVersion: v1
kind: Service
metadata:
  name: ${s.name}
  namespace: ${seed.namespace}
  labels:
${labels(s, options, 4)}
spec:
  type: ClusterIP
  selector:
    app: ${s.name}
  ports:
    - name: http
      port: 8080
      targetPort: http
`
}
