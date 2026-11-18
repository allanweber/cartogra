package io.cartogra.common.audit;

import io.cartogra.common.identity.Actor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventPortTest {

    @Test
    void recordAllRecordsEveryEventInOrderByDefault() {
        List<AuditEvent> recorded = new ArrayList<>();
        AuditEventPort port = recorded::add;
        UUID tenant = UUID.randomUUID();
        AuditEvent first = AuditEvent.of(tenant, AuditKind.TEAM, UUID.randomUUID(), "member_added", Actor.SYSTEM, Map.of());
        AuditEvent second = AuditEvent.of(tenant, AuditKind.TEAM, UUID.randomUUID(), "member_removed", Actor.SYSTEM, Map.of());

        port.recordAll(List.of(first, second));

        assertThat(recorded).containsExactly(first, second);
    }

    @Test
    void recordAllOfNothingRecordsNothing() {
        List<AuditEvent> recorded = new ArrayList<>();
        AuditEventPort port = recorded::add;

        port.recordAll(List.of());

        assertThat(recorded).isEmpty();
    }
}
