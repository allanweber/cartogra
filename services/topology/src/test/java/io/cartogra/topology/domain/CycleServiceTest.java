package io.cartogra.topology.domain;

import io.cartogra.topology.repository.DependencyGraphViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CycleServiceTest {

    @Mock DependencyGraphViewRepository dependencyGraphViewRepository;

    private CycleService service;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CycleService(dependencyGraphViewRepository);
    }

    /** Raw path as the repository returns it: closing repeat of the start id included. */
    private static List<UUID> path(UUID... members) {
        List<UUID> path = new ArrayList<>(List.of(members));
        path.add(members[0]);
        return path;
    }

    @Test
    void emptyRepositoryResultIsEmptyAndNotTruncated() {
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt())).thenReturn(List.of());

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void rawPathIsRotatedToStartAtTheSmallestUuid() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID c = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt()))
                .thenReturn(List.of(path(a, b, c)));

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).hasSize(1);
        assertThat(result.cycles().get(0).members()).containsExactly(b, c, a);
    }

    @Test
    void rotationsOfTheSameCycleDedupeToOneEntry() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID c = UUID.fromString("00000000-0000-0000-0000-000000000003");
        // Same physical cycle, found starting from each of its three members.
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt()))
                .thenReturn(List.of(path(a, b, c), path(b, c, a), path(c, a, b)));

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).hasSize(1);
        assertThat(result.cycles().get(0).members()).containsExactly(a, b, c);
    }

    @Test
    void reversedTraversalIsADifferentCycleNotADuplicate() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID c = UUID.fromString("00000000-0000-0000-0000-000000000003");
        // A->B->C->A and A->C->B->A share the same members but are different directed cycles.
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt()))
                .thenReturn(List.of(path(a, b, c), path(a, c, b)));

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).hasSize(2);
    }

    @Test
    void moreDistinctCyclesThanMaxCyclesSetsTruncated() {
        List<List<UUID>> paths = new ArrayList<>();
        for (int i = 0; i < CycleService.MAX_CYCLES + 1; i++) {
            paths.add(path(UUID.randomUUID(), UUID.randomUUID()));
        }
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt())).thenReturn(paths);

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).hasSize(CycleService.MAX_CYCLES);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void hittingTheRawRowCapSetsTruncatedEvenWithFewDistinctCycles() {
        List<List<UUID>> paths = new ArrayList<>();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < CycleService.MAX_RAW_ROWS + 1; i++) {
            paths.add(path(a, b));
        }
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), any(), anyInt(), anyInt())).thenReturn(paths);

        Cycles result = service.find(tenantId, null);

        assertThat(result.cycles()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void queriesRepositoryWithConfiguredCapsPlusOneForExactTruncationDetection() {
        when(dependencyGraphViewRepository.findCycles(eq(tenantId), eq(DependencyType.DECLARED),
                eq(CycleService.MAX_CYCLE_LENGTH), eq(CycleService.MAX_RAW_ROWS + 1))).thenReturn(List.of());

        service.find(tenantId, DependencyType.DECLARED);

        // verified via the eq() matchers above throwing on mismatch if the call shape drifts
    }
}
