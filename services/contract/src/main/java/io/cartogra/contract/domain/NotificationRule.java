package io.cartogra.contract.domain;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One tenant rule for a Producer, or for the whole tenant when {@code serviceId} is null. Webhooks and email
 * addresses are stored here in plain text; every boundary that leaves this context shows a mask instead.
 */
public record NotificationRule(
        UUID id,
        UUID tenantId,
        @Nullable UUID serviceId,
        boolean enabled,
        @Nullable String slackWebhookUrl,
        @Nullable String teamsWebhookUrl,
        List<String> emailAddresses,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant deletedAt
) {
    public static final int MAX_EMAILS = 20;

    /** Host and first path segment, then a mask. Never returns a raw webhook. */
    public static String maskWebhook(String url) {
        URI uri = URI.create(url);
        String authority = uri.getScheme() + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        String path = uri.getPath() == null ? "" : uri.getPath();
        String prefix = Arrays.stream(path.split("/"))
                .filter(segment -> !segment.isBlank())
                .findFirst()
                .map(segment -> "/" + segment + "/***")
                .orElse("/***");
        return authority + prefix;
    }

    /** First character of the local part, then a mask and the domain. Never returns a raw address. */
    public static String maskEmail(String address) {
        int at = address.indexOf('@');
        if (at < 0) {
            return "***";
        }
        return address.substring(0, 1) + "***@" + address.substring(at + 1);
    }
}
