package io.cartogra.topology.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceAlreadyResolvedException;
import io.cartogra.topology.domain.exception.UnresolvedSpanServiceNotFoundException;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnresolvedSpanServiceServiceTest {

    @Mock UnresolvedSpanServiceRepository repository;

    private UnresolvedSpanServiceService service;

    private final UUID tenant = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UnresolvedSpanServiceService(repository);
    }

    private UnresolvedSpanService row(Instant deletedAt, UnresolvedResolution resolution) {
        Instant now = Instant.now();
        return new UnresolvedSpanService(id, tenant, "stripe-gateway", now, now, 3, deletedAt, resolution);
    }

    @Test
    void listClampsLimitAndOffset() {
        when(repository.findActive(tenant, 200, 0)).thenReturn(List.of());
        when(repository.countActive(tenant)).thenReturn(7L);

        PageResult<UnresolvedSpanService> page = service.list(tenant, 5000, -3);

        assertThat(page.limit()).isEqualTo(200);
        assertThat(page.offset()).isZero();
        assertThat(page.total()).isEqualTo(7);
    }

    @Test
    void listRaisesZeroLimitToOne() {
        when(repository.findActive(tenant, 1, 0)).thenReturn(List.of());

        assertThat(service.list(tenant, 0, 0).limit()).isEqualTo(1);
    }

    @Test
    void dismissUnknownIdThrowsNotFound() {
        when(repository.findById(tenant, id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismiss(tenant, null, id))
                .isInstanceOf(UnresolvedSpanServiceNotFoundException.class);
    }

    @Test
    void dismissResolvedRowThrowsAlreadyResolved() {
        when(repository.findById(tenant, id))
                .thenReturn(Optional.of(row(Instant.now(), UnresolvedResolution.REGISTERED)));

        assertThatThrownBy(() -> service.dismiss(tenant, null, id))
                .isInstanceOf(UnresolvedSpanServiceAlreadyResolvedException.class);
        verify(repository, never()).dismiss(any(), any(), any(), any());
    }

    @Test
    void dismissLosingTheRaceThrowsAlreadyResolved() {
        when(repository.findById(tenant, id)).thenReturn(Optional.of(row(null, null)));
        when(repository.dismiss(eq(tenant), eq(id), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.dismiss(tenant, null, id))
                .isInstanceOf(UnresolvedSpanServiceAlreadyResolvedException.class);
    }

    @Test
    void dismissRecordsTheUserAndReturnsTheDismissedRow() {
        UUID user = UUID.randomUUID();
        UnresolvedSpanService dismissed = row(Instant.now(), UnresolvedResolution.DISMISSED);
        when(repository.findById(tenant, id)).thenReturn(Optional.of(row(null, null)), Optional.of(dismissed));
        when(repository.dismiss(eq(tenant), eq(id), eq(user), any())).thenReturn(true);

        assertThat(service.dismiss(tenant, user, id)).isEqualTo(dismissed);
    }
}
