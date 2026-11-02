package io.cartogra.registry.config;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.web.audit.JdbcAuditEventAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AuditConfig {

    @Bean
    public AuditEventPort auditEventPort(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcAuditEventAdapter(jdbc, objectMapper);
    }
}
