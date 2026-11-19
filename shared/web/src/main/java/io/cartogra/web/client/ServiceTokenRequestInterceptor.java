package io.cartogra.web.client;

import io.cartogra.common.identity.Roles;
import io.cartogra.web.security.InternalClaims;
import io.cartogra.web.security.InternalTokenCodec;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Signs a {@code SERVICE} token for a service-to-service call. The tenant of the token comes from the
 * {@code X-Tenant-Id} header of the outbound request, which the client must set.
 */
public class ServiceTokenRequestInterceptor implements ClientHttpRequestInterceptor {

    private final InternalTokenCodec codec;
    private final String serviceName;
    private final String audience;

    public ServiceTokenRequestInterceptor(InternalTokenCodec codec, String serviceName, String audience) {
        this.codec = codec;
        this.serviceName = serviceName;
        this.audience = audience;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String tenant = request.getHeaders().getFirst("X-Tenant-Id");
        if (tenant == null) {
            throw new IllegalStateException("A service-to-service call must set X-Tenant-Id");
        }
        var claims = new InternalClaims(UUID.fromString(tenant), "service:" + serviceName, List.of(Roles.SERVICE));
        request.getHeaders().set(InternalTokenCodec.HEADER, codec.sign(claims, audience));
        return execution.execute(request, body);
    }
}
