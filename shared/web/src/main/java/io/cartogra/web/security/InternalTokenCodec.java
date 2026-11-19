package io.cartogra.web.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs and verifies the short-lived HS256 token that the gateway and the services attach to each internal call.
 * A token is valid only for the audience (the target service) it names, and only until its {@code exp}.
 */
public class InternalTokenCodec {

    public static final String HEADER = "X-Internal-Token";

    private static final String TENANT_CLAIM = "tid";
    private static final String ROLES_CLAIM = "roles";

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final InternalTokenProperties properties;
    private final Clock clock;

    public InternalTokenCodec(InternalTokenProperties properties, Clock clock) {
        byte[] key = properties.key().getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(key);
            this.verifier = new MACVerifier(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("app.internal-token.key is not a valid HS256 key", e);
        }
        this.properties = properties;
        this.clock = clock;
    }

    public String sign(InternalClaims claims, String audience) {
        Instant now = clock.instant();
        JWTClaimsSet set = new JWTClaimsSet.Builder()
                .subject(claims.subject())
                .audience(audience)
                .claim(TENANT_CLAIM, claims.tenantId().toString())
                .claim(ROLES_CLAIM, claims.roles())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.ttl())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), set);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the internal token", e);
        }
        return jwt.serialize();
    }

    /** Empty when the token is malformed, has a wrong signature or audience, or is expired. */
    public Optional<InternalClaims> verify(String token, String audience) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet set = jwt.getJWTClaimsSet();
            Date expiry = set.getExpirationTime();
            if (expiry == null || !expiry.toInstant().isAfter(clock.instant())
                    || !set.getAudience().equals(List.of(audience))
                    || set.getSubject() == null) {
                return Optional.empty();
            }
            String tenant = set.getStringClaim(TENANT_CLAIM);
            List<String> roles = set.getStringListClaim(ROLES_CLAIM);
            if (tenant == null) {
                return Optional.empty();
            }
            return Optional.of(new InternalClaims(UUID.fromString(tenant), set.getSubject(),
                    roles == null ? List.of() : roles));
        } catch (ParseException | JOSEException | IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
