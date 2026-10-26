package io.cartogra.topology.repository;

import io.cartogra.topology.domain.SpanKind;

/** A service seen producing to or consuming from one messaging destination. */
public record MessagingEndpoint(String destination, SpanKind kind, String serviceName) {}
