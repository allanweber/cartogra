package io.cartogra.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({JwtConfig.class, ResendConfig.class, OAuthConfig.class, RateLimitProperties.class})
public class AppConfig {

    @Bean
    public JwtEncoder jwtEncoder(JwtConfig config) {
        byte[] keyBytes = config.secret().getBytes(StandardCharsets.UTF_8);
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(keyBytes)
            .algorithm(JWSAlgorithm.HS256)
            .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtConfig config) {
        byte[] keyBytes = config.secret().getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
