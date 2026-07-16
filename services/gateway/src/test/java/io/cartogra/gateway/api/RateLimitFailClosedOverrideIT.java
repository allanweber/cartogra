package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies app.rate-limit.fail-closed-on-auth-routes is a real config toggle: when explicitly
 * set to false, a Redis error on an auth route still fails OPEN (matching the pre-fix, and the
 * non-auth-route, behavior) instead of the new default fail-closed behavior.
 *
 * StringRedisTemplate is fully mocked (see RateLimitRedisFailureIT for rationale and for why
 * the stub matches all five execute() arguments, not just the fixed script/keys ones).
 */
@TestPropertySource(properties = "app.rate-limit.fail-closed-on-auth-routes=false")
class RateLimitFailClosedOverrideIT extends AbstractGatewayIT {

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void authPathFailsOpenWhenConfigOverrideDisablesFailClosed() throws Exception {
        doThrow(new RedisConnectionFailureException("simulated redis outage"))
            .when(redisTemplate)
            .execute(ArgumentMatchers.<RedisScript<String>>any(), anyList(), any(), any(), any());

        // Redis is "down" for the whole request, but the request must still be let through
        // (not 429) because fail-closed-on-auth-routes is overridden to false.
        int status = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"redis-down-override-%s@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andReturn().getResponse().getStatus();

        assertThat(status).isNotEqualTo(429);
    }
}
