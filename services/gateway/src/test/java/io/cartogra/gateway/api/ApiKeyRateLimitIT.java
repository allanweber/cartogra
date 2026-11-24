package io.cartogra.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

import io.cartogra.gateway.repository.ApiKeyRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** S-11: a key has its own bucket, so it does not drain the bucket of the tenant. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.rate-limit.default-replenish-rate=1",
    "app.rate-limit.default-burst-capacity=3"
})
class ApiKeyRateLimitIT extends AbstractApiKeyIT {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private ApiKeyRepository keyRepository;

    @Test
    void badKeysAreRateLimitedByIp() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            statuses.add(send("POST", CHECK_PATH, "ctg_live_bad_" + i, null));
        }

        assertThat(statuses.subList(0, 3)).containsOnly(401);
        assertThat(statuses.subList(5, 10)).containsOnly(429);
        // burst 3, rate 1 per second: at most 3 (+1 refill) lookups reach the repository
        verify(keyRepository, atMost(4)).findActiveByHash(anyString());
        assertThat(redisTemplate.keys("rate_limit:ip:*:apikey-lookup.tokens")).isNotEmpty();
    }

    @Test
    void keyBucketDoesNotDrainTenantBucket() throws Exception {
        String rawKey = "ctg_live_" + java.util.UUID.randomUUID();
        java.util.UUID keyId = seedActiveKey(rawKey, tenantA);

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(send("POST", CHECK_PATH, rawKey, null));
        }
        assertThat(statuses).contains(429);
        assertThat(send("POST", CHECK_PATH, rawKey, null)).isEqualTo(429);

        // the key is charged to its own bucket, and not to the IP or tenant default bucket
        assertThat(redisTemplate.hasKey("rate_limit:apikey:" + keyId + ":default.tokens")).isTrue();
        assertThat(redisTemplate.keys("rate_limit:ip:*:default.tokens")).isEmpty();
        assertThat(redisTemplate.hasKey("rate_limit:tenant:" + tenantA + ":default.tokens")).isFalse();

        assertThat(send("GET", SERVICES_PATH, null, jwt(tenantA, "ADMIN"))).isEqualTo(200);
    }
}
