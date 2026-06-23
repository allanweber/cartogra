package io.cartogra.ingestion.repository;

import io.cartogra.ingestion.domain.ScmWebhook;

import java.util.Optional;
import java.util.UUID;

public interface ScmWebhookRepository {

    /** Webhook handler lookup: the active webhook registration for a connection. */
    Optional<ScmWebhook> findByConnectionId(UUID connectionId);
}
