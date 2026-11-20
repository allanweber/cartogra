package io.cartogra.ingestion.domain;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.ingestion.api.dto.SpecPathsRequest;
import io.cartogra.ingestion.domain.exception.InvalidSpecPathsException;
import io.cartogra.ingestion.repository.SpecPathRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpecPathServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Actor ADMIN = Actor.admin(UUID.randomUUID());

    @Mock
    SpecPathRepository repository;

    List<AuditEvent> audits;
    SpecPathService service;

    @BeforeEach
    void setUp() {
        audits = new ArrayList<>();
        service = new SpecPathService(repository, new IngestionAuditRecorder(audits::add, new ObjectMapper()));
    }

    @Test
    void getGivesTheDefaultsAndTheCustomPaths() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of("spec/api.yaml"));

        SpecPaths paths = service.get(TENANT);

        assertThat(paths.defaults()).containsExactlyElementsOf(SpecDiscoverer.DEFAULT_PATHS).hasSize(5);
        assertThat(paths.custom()).containsExactly("spec/api.yaml");
    }

    @Test
    void probeOrderPutsTheCustomPathsBeforeTheDefaults() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of("b/openapi.yaml", "a/openapi.yaml"));

        assertThat(service.probeOrder(TENANT)).containsExactly(
                "b/openapi.yaml", "a/openapi.yaml",
                "openapi.yaml", "openapi.yml", "openapi.json", "api/openapi.yaml", "docs/openapi.yaml");
    }

    @Test
    void probeOrderWithNoCustomPathsIsTheDefaults() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of());

        assertThat(service.probeOrder(TENANT)).containsExactlyElementsOf(SpecDiscoverer.DEFAULT_PATHS);
    }

    @Test
    void updateSavesTheStrippedPathsAndAuditsTheChange() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of());

        SpecPaths result = service.update(TENANT, ADMIN,
                new SpecPathsRequest(List.of(" src/main/resources/openapi.yml ")));

        assertThat(result.custom()).containsExactly("src/main/resources/openapi.yml");
        verify(repository).saveCustomPaths(TENANT, List.of("src/main/resources/openapi.yml"));
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.entityType()).isEqualTo(AuditKind.SPEC_PATHS.entityType());
            assertThat(event.action()).isEqualTo("updated");
            assertThat(event.payload()).containsKey("changes");
        });
    }

    @Test
    void updateWithTheSamePathsWritesNoAudit() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of("spec/api.yaml"));

        service.update(TENANT, ADMIN, new SpecPathsRequest(List.of("spec/api.yaml")));

        assertThat(audits).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "/abs/openapi.yaml", "../openapi.yaml", "a/../openapi.yaml",
            "specs/*.yaml", "spec?.yaml", "spec[1].yaml", "spec{a,b}.yaml", "openapi.yaml", "docs/openapi.yaml"})
    void updateRejectsAnInvalidPath(String path) {
        assertThatThrownBy(() -> service.update(TENANT, ADMIN, new SpecPathsRequest(List.of(path))))
                .isInstanceOf(InvalidSpecPathsException.class);
        verify(repository, never()).saveCustomPaths(any(), anyList());
    }

    @Test
    void updateRejectsAPathLongerThan256Characters() {
        String path = "a/".repeat(128) + "x";
        assertThatThrownBy(() -> service.update(TENANT, ADMIN, new SpecPathsRequest(List.of(path))))
                .isInstanceOf(InvalidSpecPathsException.class);
    }

    @Test
    void updateAcceptsAPathOf256Characters() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of());
        String path = "a".repeat(251) + ".yaml";

        assertThat(service.update(TENANT, ADMIN, new SpecPathsRequest(List.of(path))).custom()).containsExactly(path);
    }

    @Test
    void updateRejectsMoreThanTenEntries() {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            paths.add("spec" + i + "/openapi.yaml");
        }
        assertThatThrownBy(() -> service.update(TENANT, ADMIN, new SpecPathsRequest(paths)))
                .isInstanceOf(InvalidSpecPathsException.class);
    }

    @Test
    void updateRejectsDuplicates() {
        assertThatThrownBy(() -> service.update(TENANT, ADMIN,
                new SpecPathsRequest(List.of("spec/api.yaml", "spec/api.yaml"))))
                .isInstanceOf(InvalidSpecPathsException.class)
                .satisfies(ex -> assertThat(((InvalidSpecPathsException) ex).details())
                        .containsEntry("spec/api.yaml", "is a duplicate"));
    }

    @Test
    void updateRejectsANullList() {
        assertThatThrownBy(() -> service.update(TENANT, ADMIN, new SpecPathsRequest(null)))
                .isInstanceOf(InvalidSpecPathsException.class);
    }

    @Test
    void updateWithAnEmptyListClearsTheCustomPaths() {
        when(repository.findCustomPaths(TENANT)).thenReturn(List.of("spec/api.yaml"));

        assertThat(service.update(TENANT, ADMIN, new SpecPathsRequest(Collections.emptyList())).custom()).isEmpty();
        verify(repository).saveCustomPaths(TENANT, List.of());
    }
}
