package io.cartogra.contract.domain;

import io.cartogra.contract.infrastructure.email.EmailSender;
import io.cartogra.contract.infrastructure.slack.WebhookSender;
import io.cartogra.contract.repository.NotificationRuleRepository;
import io.cartogra.contract.repository.ServiceProjectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BreakingChangeNotifierTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID SERVICE = UUID.randomUUID();
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID CHECK = UUID.randomUUID();

    @Mock NotificationRuleRepository ruleRepository;
    @Mock WebhookSender webhookSender;
    @Mock EmailSender emailSender;
    @Mock ServiceProjectionRepository serviceRepository;

    @Test
    void matchesBothScopesAndSendsInOrder() {
        NotificationRule general = rule(null, true, "https://hooks.slack.com/services/T/1", null, "a@example.com");
        NotificationRule specific = rule(SERVICE, true, null, "https://outlook.office.com/webhook/1", "b@example.com");
        NotificationRule paused = rule(SERVICE, false, "https://hooks.slack.com/services/T/2", null, "c@example.com");
        when(ruleRepository.findMatching(TENANT, SERVICE)).thenReturn(List.of(general, specific, paused));

        notifier().notifyBreaking(TENANT, SERVICE, CONTRACT, "ledger-service", CHECK,
                new CheckResult(true, 4, List.of(), List.of()));

        InOrder order = inOrder(ruleRepository, webhookSender, emailSender);
        order.verify(ruleRepository, times(1)).findMatching(TENANT, SERVICE);
        order.verify(webhookSender).send(eq(TENANT), eq(general.id()), eq(CHECK), eq(DeliveryAttempt.SLACK),
                eq(general.slackWebhookUrl()), anyString());
        order.verify(emailSender).send(eq(TENANT), eq(general.id()), eq(CHECK), eq("a@example.com"), anyString());
        order.verify(webhookSender).send(eq(TENANT), eq(specific.id()), eq(CHECK), eq(DeliveryAttempt.TEAMS),
                eq(specific.teamsWebhookUrl()), anyString());
        order.verify(emailSender).send(eq(TENANT), eq(specific.id()), eq(CHECK), eq("b@example.com"), anyString());
        verify(webhookSender, never()).send(eq(TENANT), eq(paused.id()), any(), anyString(), anyString(), anyString());
        verify(emailSender, never()).send(eq(TENANT), eq(paused.id()), any(), anyString(), anyString());
        verifyNoMoreInteractions(ruleRepository);
    }

    @Test
    void testRuleReturnsRowsInFixedOrder() {
        String slack = "https://hooks.slack.com/services/T/1";
        String teams = "https://outlook.office.com/webhook/1";
        NotificationRule rule = new NotificationRule(UUID.randomUUID(), TENANT, SERVICE, true, slack, teams,
                List.of("a@example.com", "b@example.com"), Instant.now(), Instant.now(), null);
        UUID firstSlack = UUID.randomUUID();
        UUID retrySlack = UUID.randomUUID();
        UUID teamsRow = UUID.randomUUID();
        UUID firstEmail = UUID.randomUUID();
        UUID secondEmail = UUID.randomUUID();
        when(serviceRepository.findName(TENANT, SERVICE)).thenReturn(Optional.of("ledger-service"));
        when(webhookSender.send(eq(TENANT), eq(rule.id()), isNull(), eq(DeliveryAttempt.SLACK), eq(slack),
                anyString())).thenReturn(List.of(row(firstSlack, 1), row(retrySlack, 2)));
        when(webhookSender.send(eq(TENANT), eq(rule.id()), isNull(), eq(DeliveryAttempt.TEAMS), eq(teams),
                anyString())).thenReturn(List.of(row(teamsRow, 1)));
        when(emailSender.send(eq(TENANT), eq(rule.id()), isNull(), eq("a@example.com"), anyString()))
                .thenReturn(List.of(row(firstEmail, 1)));
        when(emailSender.send(eq(TENANT), eq(rule.id()), isNull(), eq("b@example.com"), anyString()))
                .thenReturn(List.of(row(secondEmail, 1)));

        List<DeliveryAttempt> rows = notifier().testRule(TENANT, rule);

        assertThat(rows).extracting(DeliveryAttempt::id)
                .containsExactly(firstSlack, retrySlack, teamsRow, firstEmail, secondEmail);
        assertThat(rows).extracting(DeliveryAttempt::attempt).containsExactly(1, 2, 1, 1, 1);
    }

    private static DeliveryAttempt row(UUID id, int attempt) {
        return new DeliveryAttempt(id, TENANT, UUID.randomUUID(), null, attempt, DeliveryAttempt.SLACK, null, 0,
                200, null, DeliveryAttempt.SENT, Instant.now(), Instant.now());
    }

    private BreakingChangeNotifier notifier() {
        return new BreakingChangeNotifier(ruleRepository, webhookSender, emailSender, serviceRepository);
    }

    private static NotificationRule rule(UUID serviceId, boolean enabled, String slack, String teams, String email) {
        Instant now = Instant.now();
        return new NotificationRule(UUID.randomUUID(), TENANT, serviceId, enabled, slack, teams, List.of(email),
                now, now, null);
    }
}
