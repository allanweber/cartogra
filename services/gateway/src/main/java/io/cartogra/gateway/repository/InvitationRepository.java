package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.Invitation;

import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository {
    Invitation save(Invitation invitation);
    Optional<Invitation> findByToken(String token);
    void markAccepted(UUID id);
}
