package io.cartogra.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.cartogra.web.security.InternalTokenCodec;
import io.cartogra.web.security.InternalTokens;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

class ServiceTokenRequestInterceptorTest {

    private final InternalTokenCodec codec = InternalTokens.codec(Clock.systemUTC());
    private final ServiceTokenRequestInterceptor interceptor = new ServiceTokenRequestInterceptor(codec, "contract", "registry");

    @Test
    void signsServiceTokenForTheTenantOfTheCall() throws Exception {
        UUID tenant = UUID.randomUUID();
        var request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://registry/internal/x"));
        request.getHeaders().set("X-Tenant-Id", tenant.toString());
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(mock(ClientHttpResponse.class));

        interceptor.intercept(request, new byte[0], execution);

        var claims = codec.verify(request.getHeaders().getFirst(InternalTokenCodec.HEADER), "registry").orElseThrow();
        assertThat(claims.tenantId()).isEqualTo(tenant);
        assertThat(claims.subject()).isEqualTo("service:contract");
        assertThat(claims.roles()).isEqualTo(List.of("SERVICE"));
    }

    @Test
    void refusesACallWithoutTenant() {
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://registry/internal/x"));
        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], mock(ClientHttpRequestExecution.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
