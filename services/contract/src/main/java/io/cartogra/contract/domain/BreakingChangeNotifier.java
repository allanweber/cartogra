package io.cartogra.contract.domain;

import io.cartogra.contract.infrastructure.email.EmailSender;
import io.cartogra.contract.infrastructure.slack.WebhookSender;
import io.cartogra.contract.repository.NotificationRuleRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sends a breaking check to every enabled matching Notification Rule, in the fixed destination order. */
@Service
public class BreakingChangeNotifier {

    private final NotificationRuleRepository ruleRepository;
    private final WebhookSender webhookSender;
    private final EmailSender emailSender;
    private final ServiceProjectionRepository serviceRepository;

    public BreakingChangeNotifier(NotificationRuleRepository ruleRepository,
                                  WebhookSender webhookSender, EmailSender emailSender,
                                  ServiceProjectionRepository serviceRepository) {
        this.ruleRepository = ruleRepository;
        this.webhookSender = webhookSender;
        this.emailSender = emailSender;
        this.serviceRepository = serviceRepository;
    }

    /** Loads the tenant-wide rule and the Service rule in one query, then sends their enabled destinations. */
    public void notifyBreaking(UUID tenantId, UUID serviceId, @Nullable UUID contractId, String serviceName,
                               UUID checkId, CheckResult result) {
        String text = BreakingChangeMessage.render(serviceName, contractId, result).text();
        for (NotificationRule rule : ruleRepository.findMatching(tenantId, serviceId)) {
            sendToRule(tenantId, rule, checkId, text);
        }
    }

    /** Tests only the saved rule; the caller refuses a disabled rule before this method runs. */
    public List<DeliveryAttempt> testRule(UUID tenantId, NotificationRule rule) {
        String serviceName = rule.serviceId() == null ? "all services"
                : serviceRepository.findName(tenantId, rule.serviceId()).orElse(rule.serviceId().toString());
        String text = BreakingChangeMessage.test(serviceName, null).text();
        return sendToRule(tenantId, rule, null, text);
    }

    private List<DeliveryAttempt> sendToRule(UUID tenantId, NotificationRule rule, @Nullable UUID checkId,
                                             String text) {
        List<DeliveryAttempt> rows = new ArrayList<>();
        if (!rule.enabled()) {
            return rows;
        }
        if (rule.slackWebhookUrl() != null) {
            rows.addAll(webhookSender.send(tenantId, rule.id(), checkId, DeliveryAttempt.SLACK,
                    rule.slackWebhookUrl(), text));
        }
        if (rule.teamsWebhookUrl() != null) {
            rows.addAll(webhookSender.send(tenantId, rule.id(), checkId, DeliveryAttempt.TEAMS,
                    rule.teamsWebhookUrl(), text));
        }
        for (String email : rule.emailAddresses()) {
            rows.addAll(emailSender.send(tenantId, rule.id(), checkId, email, text));
        }
        return rows;
    }
}
