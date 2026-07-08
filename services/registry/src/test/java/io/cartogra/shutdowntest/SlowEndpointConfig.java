package io.cartogra.shutdowntest;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately outside the {@code io.cartogra.registry} / {@code io.cartogra.ingestion} /
 * {@code io.cartogra.gateway} component-scan trees, so it's only registered when a test
 * explicitly passes this class as a primary source to {@code SpringApplicationBuilder} - never
 * picked up implicitly by the app's own {@code @ComponentScan}.
 *
 * <p>No explicit {@code @Bean} method: {@code @Configuration} classes have their annotated member
 * classes auto-registered by {@code ConfigurationClassParser}, so an explicit {@code @Bean} here
 * would double-register {@link SlowController} and produce an "ambiguous mapping" error.
 */
@Configuration
public class SlowEndpointConfig {

    public static final AtomicBoolean HANDLER_STARTED = new AtomicBoolean(false);

    @RestController
    public static class SlowController {
        @GetMapping("/test/slow")
        public String slow() throws InterruptedException {
            HANDLER_STARTED.set(true);
            Thread.sleep(3000);
            return "done";
        }
    }
}
