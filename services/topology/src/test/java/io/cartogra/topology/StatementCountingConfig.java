package io.cartogra.topology;

import io.cartogra.test.SqlStatementCounter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/** Wraps the DataSource so an IT can count statements with {@link SqlStatementCounter#count}. */
@TestConfiguration
public class StatementCountingConfig {

    @Bean
    static BeanPostProcessor statementCountingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource ds ? SqlStatementCounter.wrap(ds) : bean;
            }
        };
    }
}
