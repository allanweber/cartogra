package io.cartogra.gateway.config;

import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class OtlpTracingConfiguration {

    private static final String TRACE_ENDPOINT_PROPERTY = "management.opentelemetry.tracing.export.otlp.endpoint";

    private static final String DEFAULT_TRACE_ENDPOINT = "http://localhost:4318/v1/traces";

    @Bean
    SpanExporter otlpHttpSpanExporter(Environment environment, ObjectProvider<MeterProvider> meterProvider) {
        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(environment.getProperty(TRACE_ENDPOINT_PROPERTY, DEFAULT_TRACE_ENDPOINT));
        meterProvider.ifAvailable(builder::setMeterProvider);
        return builder.build();
    }
}