package io.cartogra.gateway.domain;

import io.cartogra.gateway.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthenticatorTest {

    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(repository);

    @Test
    void hashIsSha256Hex() {
        assertThat(ApiKeyAuthenticator.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void activeKeyIsFoundByHashAndTouched() {
        var key = new ApiKey(UUID.randomUUID(), UUID.randomUUID(), Set.of("ci:check"));
        when(repository.findActiveByHash(ApiKeyAuthenticator.hash("cgk_k"))).thenReturn(Optional.of(key));

        assertThat(authenticator.authenticate("cgk_k")).contains(key);
        verify(repository).touchLastUsed(key.id());
    }

    @Test
    void unknownKeyIsNotTouched() {
        when(repository.findActiveByHash(ApiKeyAuthenticator.hash("cgk_k"))).thenReturn(Optional.empty());

        assertThat(authenticator.authenticate("cgk_k")).isEmpty();
        verify(repository, never()).touchLastUsed(org.mockito.ArgumentMatchers.any());
    }
}
