package io.cartogra.gateway.infrastructure.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.springframework.stereotype.Component;
import reactor.util.context.ContextView;

@Component
public class TraceContext {

    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    public String currentTraceId(ContextView reactorContext) {
        Observation obs = reactorContext.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
        if (obs == null) {
            return INVALID_TRACE_ID;
        }
        TracingObservationHandler.TracingContext tracingCtx =
            obs.getContext().get(TracingObservationHandler.TracingContext.class);
        if (tracingCtx == null || tracingCtx.getSpan() == null) {
            return INVALID_TRACE_ID;
        }
        return tracingCtx.getSpan().context().traceId();
    }
}
