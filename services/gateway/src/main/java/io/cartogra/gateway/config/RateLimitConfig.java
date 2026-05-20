package io.cartogra.gateway.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RateLimitConfig {

    // ARGV[3] is epoch milliseconds; dividing by 1000 gives fractional seconds so that
    // requests spanning a clock-second boundary do not accidentally replenish tokens.
    private static final String TOKEN_BUCKET_SCRIPT = """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local fill_time = capacity / rate
            local ttl = math.floor(fill_time * 2)
            local last_tokens = tonumber(redis.call('get', tokens_key))
            if last_tokens == nil then
              last_tokens = capacity
            end
            local last_refreshed_ms = tonumber(redis.call('get', timestamp_key))
            if last_refreshed_ms == nil then
              last_refreshed_ms = 0
            end
            local delta_seconds = math.max(0, (now_ms - last_refreshed_ms) / 1000.0)
            local filled_tokens = math.min(capacity, last_tokens + (delta_seconds * rate))
            local allowed = filled_tokens >= 1
            local new_tokens = filled_tokens
            if allowed then
              new_tokens = filled_tokens - 1
            end
            redis.call('setex', tokens_key, ttl, new_tokens)
            redis.call('setex', timestamp_key, ttl, now_ms)
            if allowed then return '1' else return '0' end
            """;

    @Bean
    public RedisScript<String> rateLimitScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(TOKEN_BUCKET_SCRIPT);
        script.setResultType(String.class);
        return script;
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
