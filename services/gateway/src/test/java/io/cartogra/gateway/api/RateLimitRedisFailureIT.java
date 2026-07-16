package io.cartogra.gateway.api;

import io.cartogra.gateway.AbstractGatewayIT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the Redis-outage degrade policy in RateLimitFilter.isAllowed: with
 * app.rate-limit.fail-closed-on-auth-routes=true (the shipped default in application.yml,
 * pinned explicitly here via @TestPropertySource for determinism across the whole gateway test
 * suite -- see RateLimitFailClosedOverrideIT for the counterpart with the flag set to false), a
 * Redis error must fail CLOSED on auth routes (429) but still fail OPEN on non-auth routes
 * (no availability regression).
 *
 * StringRedisTemplate is fully mocked here (not spied against the real Testcontainers Redis)
 * because we need every call to the rate-limit Lua script to blow up to simulate an outage --
 * RateLimitFilter is the only production consumer of this bean, so a full mock doesn't risk
 * masking unrelated behavior. The stub matches five arguments (script, keys, replenishRate,
 * burstCapacity, timestamp) because RateLimitFilter.isAllowed passes its Object... varargs as
 * three individually-matched elements, not as a single array.
 */
@TestPropertySource(properties = "app.rate-limit.fail-closed-on-auth-routes=true")
class RateLimitRedisFailureIT extends AbstractGatewayIT {

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void authPathFailsClosedWhenRedisErrors() throws Exception {
        doThrow(new RedisConnectionFailureException("simulated redis outage"))
            .when(redisTemplate)
            .execute(ArgumentMatchers.<RedisScript<String>>any(), anyList(), any(), any(), any());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"redis-down-%s@test.com","password":"password123","tenantId":"%s"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID())))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void nonAuthPathFailsOpenWhenRedisErrors() throws Exception {
        doThrow(new RedisConnectionFailureException("simulated redis outage"))
            .when(redisTemplate)
            .execute(ArgumentMatchers.<RedisScript<String>>any(), anyList(), any(), any(), any());

        // Non-auth path: request must still proceed through the filter chain rather than 429.
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().is(HttpStatus.OK.value()));
    }
}
