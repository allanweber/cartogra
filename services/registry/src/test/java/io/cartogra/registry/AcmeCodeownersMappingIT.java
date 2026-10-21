package io.cartogra.registry;

import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@code ownership.resolved} with the handles Ingestion emits for the Acme Fintech seed's CODEOWNERS files
 * ({@code seed/acme-fintech/src/repos.ts}) and asserts the resulting ownership matches {@code seed-data.json}.
 */
@SpringBootTest
class AcmeCodeownersMappingIT {

    private static final Path SEED = Path.of("../../seed/acme-fintech/seed-data.json");
    private static final String PROJECT = "Acme";

    @MockitoBean
    ServiceLifecycleEventProducer eventProducer;

    @MockitoBean
    TeamLifecycleEventProducer teamEventProducer;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ServiceService serviceService;

    @Autowired
    private TeamRepository teamRepository;

    private final ObjectMapper mapper = new ObjectMapper();
    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        tenantId = UUID.randomUUID();
        insertTenant(tenantId);
    }

    @Test
    void codeownersHandlesAssignTheSeventeenOwnedServicesAndLeaveTheThreeOrphans() {
        JsonNode seed = mapper.readTree(SEED.toFile());
        Map<String, UUID> teamIds = new HashMap<>();
        for (JsonNode team : seed.get("teams")) {
            teamIds.put(team.asString(), insertTeam(tenantId, team.asString()));
        }
        Map<String, UUID> serviceIds = new HashMap<>();
        for (JsonNode service : seed.get("services")) {
            String name = service.get("name").asString();
            serviceIds.put(name, insertScmService(tenantId, name));
        }

        for (JsonNode service : seed.get("services")) {
            JsonNode team = service.get("team");
            List<String> ownerTeams = team.isNull() ? List.of() : List.of(acmeTeamSlug(team.asString()));
            serviceService.resolveOwnership(new OwnershipResolvedPayload(
                    tenantId, UUID.randomUUID(), PROJECT + "/" + service.get("name").asString(), ownerTeams, Map.of()));
        }

        int owned = 0;
        int orphans = 0;
        for (JsonNode service : seed.get("services")) {
            UUID actual = teamIdOf(serviceIds.get(service.get("name").asString()));
            JsonNode team = service.get("team");
            if (team.isNull()) {
                assertThat(actual).as(service.get("name").asString()).isNull();
                orphans++;
            } else {
                assertThat(actual).as(service.get("name").asString()).isEqualTo(teamIds.get(team.asString()));
                owned++;
            }
        }
        assertThat(owned).isEqualTo(17);
        assertThat(orphans).isEqualTo(3);
    }

    @Test
    void slugLookupIsTenantScopedAndIgnoresSoftDeletedTeams() {
        UUID platform = insertTeam(tenantId, "Platform Team");
        UUID retired = insertTeam(tenantId, "Retired");
        jdbc.update("UPDATE teams SET deleted_at = now() WHERE id = :id", new MapSqlParameterSource("id", retired));
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        insertTeam(otherTenant, "Ledger");

        assertThat(teamRepository.findByTenantAndSlug(tenantId, "platform-team")).get()
                .extracting(t -> t.id()).isEqualTo(platform);
        assertThat(teamRepository.findByTenantAndSlug(tenantId, "retired")).isEmpty();
        assertThat(teamRepository.findByTenantAndSlug(tenantId, "ledger")).isEmpty();
    }

    /** Replica of {@code teamSlug()} in {@code seed/acme-fintech/src/repos.ts}. */
    private static String acmeTeamSlug(String team) {
        return team.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private void insertTenant(UUID id) {
        jdbc.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                SELECT :tenantId, 'Acme', 'acme-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free')
                WHERE NOT EXISTS (SELECT 1 FROM tenants WHERE tenant_id = :tenantId)
                """, new MapSqlParameterSource("tenantId", id));
    }

    private UUID insertTeam(UUID tenant, String name) {
        return jdbc.queryForObject(
                "INSERT INTO teams (tenant_id, name) VALUES (:tenantId, :name) RETURNING id",
                new MapSqlParameterSource().addValue("tenantId", tenant).addValue("name", name),
                UUID.class);
    }

    private UUID insertScmService(UUID tenant, String name) {
        return jdbc.queryForObject("""
                INSERT INTO services (tenant_id, name, external_id, source, repository_url)
                VALUES (:tenantId, :name, :externalId, 'azuredevops', :url)
                RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenant)
                        .addValue("name", name)
                        .addValue("externalId", PROJECT + "/" + name)
                        .addValue("url", "https://dev.azure.com/acme/" + PROJECT + "/_git/" + name),
                UUID.class);
    }

    private UUID teamIdOf(UUID serviceId) {
        return jdbc.queryForObject("SELECT team_id FROM services WHERE id = :id",
                new MapSqlParameterSource("id", serviceId), UUID.class);
    }
}
