package io.cartogra.gateway.repository;

import java.util.UUID;

public interface TeamMembershipRepository {
    boolean teamExists(UUID tenantId, UUID teamId);
    void addMember(UUID tenantId, UUID teamId, UUID userId);
}
