package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.application.dto.AssignOwnerCommand;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.application.repository.TeamRepository;
import io.cartogra.registry.application.usecase.AssignOwnerUseCase;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

@Component
public class OwnershipResolvedConsumer {

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> StreamSupport.stream(carrier.spliterator(), false)
                    .map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header h = carrier.lastHeader(key);
            return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(OwnershipResolvedConsumer.class);
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final ServiceRepository serviceRepository;
    private final TeamRepository teamRepository;
    private final AssignOwnerUseCase assignOwnerUseCase;
    private final ObjectMapper objectMapper;

    public OwnershipResolvedConsumer(
            ServiceRepository serviceRepository,
            TeamRepository teamRepository,
            AssignOwnerUseCase assignOwnerUseCase,
            ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.teamRepository = teamRepository;
        this.assignOwnerUseCase = assignOwnerUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "cartogra.ingestion.ownership.resolved", groupId = "${spring.kafka.consumer.group-id:registry-ownership-consumer}")
    public void consume(ConsumerRecord<String, String> record) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<OwnershipResolvedPayload> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<OwnershipResolvedPayload>>() {});
            processOwnership(envelope.payload());
        } catch (Exception e) {
            log.error("Failed to process ownership.resolved event key={}: {}", record.key(), e.getMessage(), e);
        }
    }

    private void processOwnership(OwnershipResolvedPayload payload) {
        Optional<Service> serviceOpt = serviceRepository.findByRepositoryPath(
                payload.tenantId(), payload.repositoryFullName());

        if (serviceOpt.isEmpty()) {
            log.warn("ownership.resolved: no service found for repository={} tenant={}",
                    payload.repositoryFullName(), payload.tenantId());
            return;
        }

        Service service = serviceOpt.get();

        if (service.teamId() != null) {
            log.warn("ownership.resolved: service={} already has team={}, skipping (idempotent)", service.id(), service.teamId());
            return;
        }

        if (payload.ownerTeams().isEmpty()) {
            log.warn("ownership.resolved: no ownerTeams for repository={} tenant={}",
                    payload.repositoryFullName(), payload.tenantId());
            return;
        }

        if (payload.ownerTeams().size() > 1) {
            log.warn("ownership.resolved: ambiguous ownerTeams={} for repository={} tenant={}, skipping auto-assign",
                    payload.ownerTeams(), payload.repositoryFullName(), payload.tenantId());
            return;
        }

        String teamName = payload.ownerTeams().get(0);
        Optional<Team> teamOpt = teamRepository.findByTenantAndName(payload.tenantId(), teamName);

        if (teamOpt.isEmpty()) {
            log.warn("ownership.resolved: team='{}' not found in registry for tenant={}, repository={}",
                    teamName, payload.tenantId(), payload.repositoryFullName());
            return;
        }

        assignOwnerUseCase.execute(new AssignOwnerCommand(
                payload.tenantId(), service.id(), teamOpt.get().id(), SYSTEM_ACTOR));

        log.info("ownership.resolved: assigned team={} to service={} via CODEOWNERS",
                teamOpt.get().id(), service.id());
    }
}
