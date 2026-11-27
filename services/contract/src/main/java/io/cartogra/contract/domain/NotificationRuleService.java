package io.cartogra.contract.domain;

import io.cartogra.common.api.PageResult;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.audit.AuditKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.common.identity.Roles;
import io.cartogra.contract.api.dto.NotificationRuleRequest;
import io.cartogra.contract.domain.exception.InvalidParameterException;
import io.cartogra.contract.domain.exception.NotificationRuleConflictException;
import io.cartogra.contract.domain.exception.NotificationRuleDisabledException;
import io.cartogra.contract.domain.exception.NotificationRuleNotFoundException;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import io.cartogra.contract.repository.NotificationRuleRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class NotificationRuleService {

    static final int MAX_PAGE_SIZE = 100;
    static final int HISTORY_LIMIT = 50;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final NotificationRuleRepository repository;
    private final DeliveryAttemptRepository logRepository;
    private final BreakingChangeNotifier notifier;
    private final AuditEventPort auditEventPort;

    public NotificationRuleService(NotificationRuleRepository repository, DeliveryAttemptRepository logRepository,
                                   BreakingChangeNotifier notifier, AuditEventPort auditEventPort) {
        this.repository = repository;
        this.logRepository = logRepository;
        this.notifier = notifier;
        this.auditEventPort = auditEventPort;
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public NotificationRule create(UUID tenantId, Actor actor, NotificationRuleRequest request) {
        String slack = normalized(request.slackWebhookUrl());
        String teams = normalized(request.teamsWebhookUrl());
        List<String> emails = request.emailAddresses() == null ? List.of()
                : request.emailAddresses().stream().map(String::trim).toList();
        validate(slack, teams, emails);
        Instant now = Instant.now();
        NotificationRule rule = new NotificationRule(UUID.randomUUID(), tenantId, request.serviceId(),
                true, slack, teams, emails, now, now, null);
        try {
            repository.insert(rule);
        } catch (DuplicateKeyException _) {
            throw new NotificationRuleConflictException();
        }
        audit(rule, "created", actor);
        return rule;
    }

    public NotificationRule get(UUID tenantId, UUID id) {
        return find(tenantId, id);
    }

    /** Rules of the tenant, newest first, in the shared page shape. */
    public PageResult<NotificationRule> list(UUID tenantId, int limit, int offset) {
        int size = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int from = Math.max(offset, 0);
        return PageResult.of(repository.listLive(tenantId, size, from), repository.countLive(tenantId), size, from);
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public NotificationRule update(UUID tenantId, Actor actor, UUID id, NotificationRuleRequest request) {
        find(tenantId, id);
        String slack = normalized(request.slackWebhookUrl());
        String teams = normalized(request.teamsWebhookUrl());
        List<String> emails = request.emailAddresses() == null ? List.of()
                : request.emailAddresses().stream().map(String::trim).toList();
        validate(slack, teams, emails);
        NotificationRule updated = repository.update(tenantId, id, slack, teams, emails, Instant.now())
                .orElseThrow(() -> new NotificationRuleNotFoundException(id));
        audit(updated, "updated", actor);
        return updated;
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public NotificationRule setEnabled(UUID tenantId, Actor actor, UUID id, boolean enabled) {
        find(tenantId, id);
        String action = enabled ? "enabled" : "disabled";
        NotificationRule updated = repository.setEnabled(tenantId, id, enabled, Instant.now())
                .orElseThrow(() -> new NotificationRuleNotFoundException(id));
        audit(updated, action, actor);
        return updated;
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public NotificationRule patchEnabled(UUID tenantId, Actor actor, UUID id, NotificationRuleRequest request) {
        if (request.enabled() == null) {
            throw new InvalidParameterException("enabled", "is required on this path");
        }
        if (request.serviceId() != null || request.slackWebhookUrl() != null || request.teamsWebhookUrl() != null
                || request.emailAddresses() != null) {
            throw new InvalidParameterException("request", "only enabled is accepted on this path");
        }
        return setEnabled(tenantId, actor, id, request.enabled());
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public List<DeliveryAttempt> test(UUID tenantId, Actor actor, UUID id) {
        NotificationRule rule = find(tenantId, id);
        if (!rule.enabled()) {
            throw new NotificationRuleDisabledException(id);
        }
        return notifier.testRule(tenantId, rule);
    }

    /** The latest 50 Delivery Attempts of one rule, newest first, in the shared page shape. */
    public PageResult<DeliveryAttempt> listAttempts(UUID tenantId, Actor actor, UUID id) {
        find(tenantId, id);
        return PageResult.of(logRepository.listByRule(tenantId, id, HISTORY_LIMIT, 0),
                logRepository.countByRule(tenantId, id), HISTORY_LIMIT, 0);
    }

    @PreAuthorize(Roles.CAN_WRITE)
    public void delete(UUID tenantId, Actor actor, UUID id) {
        NotificationRule existing = find(tenantId, id);
        if (!repository.softDelete(tenantId, id, Instant.now())) {
            throw new NotificationRuleNotFoundException(id);
        }
        audit(existing, "deleted", actor);
    }

    private NotificationRule find(UUID tenantId, UUID id) {
        return repository.findLive(tenantId, id).orElseThrow(() -> new NotificationRuleNotFoundException(id));
    }

    private static @Nullable String normalized(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void validate(@Nullable String slack, @Nullable String teams, List<String> emails) {
        if (slack == null && teams == null && emails.isEmpty()) {
            throw new InvalidParameterException("destinations", "at least one destination is required");
        }
        if (slack != null) {
            validateWebhook(slack, "slackWebhookUrl");
        }
        if (teams != null) {
            validateWebhook(teams, "teamsWebhookUrl");
        }
        if (emails.size() > NotificationRule.MAX_EMAILS) {
            throw new InvalidParameterException("emailAddresses", "at most " + NotificationRule.MAX_EMAILS
                    + " addresses are allowed");
        }
        for (String email : emails) {
            if (email.length() > 255 || !EMAIL.matcher(email).matches()) {
                throw new InvalidParameterException("emailAddresses", "must hold valid addresses of at most 255 characters");
            }
        }
    }

    private static void validateWebhook(String url, String field) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException _) {
            throw new InvalidParameterException(field, "must be a valid URL");
        }
        String host = uri.getHost();
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        if (localHttp) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
            throw new InvalidParameterException(field, "must use https, or http only on localhost or 127.0.0.1");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidParameterException(field, "must not contain user info");
        }
        if (!host.contains(".")) {
            throw new InvalidParameterException(field, "must use a host with a domain suffix");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".localhost")
                || lower.endsWith(".localdomain")) {
            throw new InvalidParameterException(field, "must not use an internal host name");
        }
        if (host.startsWith("[") || host.contains(":") || isBlockedIpv4(host)) {
            throw new InvalidParameterException(field, "must not use an internal IP address");
        }
    }

    private static boolean isBlockedIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("\\d{1,3}")) {
                return false;
            }
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                return false;
            }
        }
        return octets[0] == 0 || octets[0] == 10 || octets[0] == 127
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    private void audit(NotificationRule rule, String action, Actor actor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (rule.serviceId() != null) {
            payload.put("serviceId", rule.serviceId());
        }
        payload.put("enabled", rule.enabled());
        payload.put("slackSet", rule.slackWebhookUrl() != null);
        payload.put("teamsSet", rule.teamsWebhookUrl() != null);
        payload.put("emailCount", rule.emailAddresses().size());
        auditEventPort.record(AuditEvent.of(rule.tenantId(), AuditKind.NOTIFICATION_RULE, rule.id(), action, actor,
                payload));
    }
}
