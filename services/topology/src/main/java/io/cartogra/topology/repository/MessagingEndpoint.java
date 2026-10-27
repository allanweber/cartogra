package io.cartogra.topology.repository;

import io.cartogra.topology.domain.SpanKind;

import java.util.Locale;

/** A service seen producing to or consuming from one messaging destination; the service name is lowercased. */
public record MessagingEndpoint(String destination, SpanKind kind, String serviceName) {

    public MessagingEndpoint {
        serviceName = serviceName.toLowerCase(Locale.ROOT);
    }
}
