package io.cartogra.registry.config;

import io.cartogra.web.lock.AdvisoryLockRepository;
import io.cartogra.web.lock.JdbcAdvisoryLockRepository;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LockConfig {

    @Bean
    public AdvisoryLockRepository advisoryLockRepository(DataSource dataSource) {
        return new JdbcAdvisoryLockRepository(dataSource);
    }
}
