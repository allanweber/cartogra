package io.cartogra.gateway.domain;

import io.cartogra.gateway.config.TenantBootstrapProperties;
import io.cartogra.gateway.repository.TenantRepository;
import io.cartogra.gateway.repository.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantBootstrapServiceTest {

    @Test
    void neverFlagsAnExistingTenantAsPublicSandbox() {
        UUID id = UUID.randomUUID();
        TenantRepository tenants = Mockito.mock(TenantRepository.class);
        UserRepository users = Mockito.mock(UserRepository.class);
        when(tenants.findByTenantId(id)).thenReturn(Optional.of(
            new Tenant(id, id, "Real customer", "real", "free", null, null, null, false)));
        when(users.findByTenantAndEmail(any(), any())).thenReturn(Optional.of(Mockito.mock(User.class)));

        new TenantBootstrapService(
            new TenantBootstrapProperties(id, "Real customer", "free", "a@b.invalid", "pw", true),
            tenants, users, Mockito.mock(PasswordEncoder.class)).ensureExists();

        verify(tenants, never()).save(any());
    }
}
