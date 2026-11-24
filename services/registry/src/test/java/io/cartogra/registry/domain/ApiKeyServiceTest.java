package io.cartogra.registry.domain;

import io.cartogra.registry.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    @Test
    void keyFormatAndHash() {
        String key = ApiKeyService.generateKey();

        assertThat(key).startsWith("cgk_");
        assertThat(key.substring(4)).matches("[A-Za-z0-9_-]{43}");
        assertThat(ApiKeyService.displayPrefix(key)).isEqualTo(key.substring(0, 12));
        assertThat(ApiKeyService.hash(key)).matches("[0-9a-f]{64}").isEqualTo(ApiKeyService.hash(key));
        assertThat(ApiKeyService.generateKey()).isNotEqualTo(key);
        assertThat(ApiKeyService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void statusFollowsTimestamps() {
        Instant now = Instant.now();
        assertThat(key(null, null).statusAt(now)).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key(now.plusSeconds(60), null).statusAt(now)).isEqualTo(ApiKeyStatus.ACTIVE);
        assertThat(key(now, null).statusAt(now)).isEqualTo(ApiKeyStatus.EXPIRED);
        assertThat(key(now.minusSeconds(1), now.minusSeconds(5)).statusAt(now)).isEqualTo(ApiKeyStatus.REVOKED);
    }

    @Test
    void unknownScopeIsRejected() {
        assertThat(ApiKeyScope.parse("ci:check")).isEqualTo(ApiKeyScope.CI_CHECK);
        assertThatThrownBy(() -> ApiKeyScope.parse("admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listClampsLimit() {
        ApiKeyRepository repository = mock(ApiKeyRepository.class);
        UUID tenant = UUID.randomUUID();
        when(repository.findAll(eq(tenant), anyInt(), anyInt())).thenReturn(List.of());
        ApiKeyService service = new ApiKeyService(repository, null, null, null);

        var big = service.list(tenant, 100_000, -5);
        var small = service.list(tenant, 0, 10);

        assertThat(big.limit()).isEqualTo(200);
        assertThat(big.offset()).isZero();
        assertThat(small.limit()).isEqualTo(1);
        verify(repository).findAll(tenant, 200, 0);
        verify(repository).findAll(tenant, 1, 10);
    }

    private static ApiKey key(Instant expiresAt, Instant revokedAt) {
        Instant t = Instant.now();
        return new ApiKey(UUID.randomUUID(), UUID.randomUUID(), "n", "h", "cgk_abcdefgh", List.of("ci:check"),
                expiresAt, revokedAt, null, UUID.randomUUID(), t, t);
    }
}
