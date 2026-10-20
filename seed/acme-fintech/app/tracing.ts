import { context, propagation, SpanKind, SpanStatusCode, trace, type Span } from '@opentelemetry/api'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { resourceFromAttributes } from '@opentelemetry/resources'
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-base'
import { NodeTracerProvider } from '@opentelemetry/sdk-trace-node'

export interface TracingConfig {
  serviceName: string
  tenantId: string
  namespace: string
  otlpEndpoint: string
}

export interface Tracing {
  clientCall: (target: string, serverAddress: string, run: (headers: Record<string, string>) => Promise<void>) => Promise<void>
  serverSpan: (headers: Record<string, string | string[] | undefined>, run: () => void) => void
  shutdown: () => Promise<void>
}

export function startTracing(config: TracingConfig): Tracing {
  const exporter = new OTLPTraceExporter({ url: `${config.otlpEndpoint.replace(/\/$/, '')}/v1/traces` })
  const provider = new NodeTracerProvider({
    resource: resourceFromAttributes({
      'service.name': config.serviceName,
      'service.namespace': config.namespace,
      'cartogra.tenant.id': config.tenantId,
    }),
    spanProcessors: [new BatchSpanProcessor(exporter, { scheduledDelayMillis: 1000 })],
  })
  provider.register()
  const tracer = trace.getTracer('acme-fintech-service')

  return {
    async clientCall(target, serverAddress, run) {
      await tracer.startActiveSpan(
        `GET ${target}`,
        {
          kind: SpanKind.CLIENT,
          attributes: { 'http.request.method': 'GET', 'peer.service': target, 'server.address': serverAddress },
        },
        async (span: Span) => {
          try {
            const headers: Record<string, string> = {}
            propagation.inject(context.active(), headers)
            await run(headers)
          } catch (error) {
            span.setStatus({ code: SpanStatusCode.ERROR, message: String(error) })
            throw error
          } finally {
            span.end()
          }
        },
      )
    },
    serverSpan(headers, run) {
      const parent = propagation.extract(context.active(), headers)
      const span = tracer.startSpan('GET /work', { kind: SpanKind.SERVER, attributes: { 'http.request.method': 'GET' } }, parent)
      try {
        run()
      } finally {
        span.end()
      }
    },
    shutdown: () => provider.shutdown(),
  }
}
