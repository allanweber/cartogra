package io.cartogra.contract;

import io.cartogra.contract.config.NotificationEmailProperties;
import io.cartogra.contract.config.NotificationWebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({NotificationEmailProperties.class, NotificationWebhookProperties.class})
public class ContractApplication {
    public static void main(String[] args) {
        SpringApplication.run(ContractApplication.class, args);
    }
}
