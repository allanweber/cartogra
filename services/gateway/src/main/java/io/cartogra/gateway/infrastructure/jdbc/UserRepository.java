package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByEmail(String email);
    Optional<User> findByTenantAndEmail(UUID tenantId, String email);
    Optional<User> findByVerificationToken(String token);
    User save(User user);
}
