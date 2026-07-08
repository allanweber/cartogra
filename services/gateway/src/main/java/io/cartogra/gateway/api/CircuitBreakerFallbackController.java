package io.cartogra.gateway.api;

import io.cartogra.gateway.domain.exception.ServiceUnavailableException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CircuitBreakerFallbackController {

    @RequestMapping("/internal/fallback/{service}")
    public void fallback(@PathVariable String service) {
        throw new ServiceUnavailableException(service);
    }
}
