package io.cartogra.gateway.repository;

import io.cartogra.gateway.domain.User;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByEmail(String email);
    List<User> findAllByEmail(String email);
    Optional<User> findByTenantAndEmail(UUID tenantId, String email);
    List<User> findAllByTenant(UUID tenantId);
    List<User> findByIds(UUID tenantId, Set<UUID> ids);
    Optional<User> findByVerificationToken(String token);
    Optional<User> findByPasswordResetToken(String token);
    long count(UUID tenantId);
    User save(User user);
    void softDelete(UUID tenantId, UUID id);
}
