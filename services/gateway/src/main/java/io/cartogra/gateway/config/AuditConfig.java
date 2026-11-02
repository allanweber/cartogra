package io.cartogra.gateway.config;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.web.audit.JdbcAuditEventAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Gateway shares registry's schema, so it writes {@code audit_events} directly, in the mutation's transaction. */
@Configuration
public class AuditConfig {

    @Bean
    public AuditEventPort auditEventPort(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcAuditEventAdapter(jdbc, objectMapper);
    }
}
