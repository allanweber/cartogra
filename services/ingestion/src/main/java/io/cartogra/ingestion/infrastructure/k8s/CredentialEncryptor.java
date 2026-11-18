package io.cartogra.ingestion.infrastructure.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class CredentialEncryptor {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptor.class);
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey key;

    /**
     * Fails closed: without a key, startup fails unless {@code allowPlaintext} is true (local development and tests
     * only). Then, values are stored in plain text.
     */
    public CredentialEncryptor(
            @Value("${ingestion.k8s.credentials-key:}") String base64Key,
            @Value("${ingestion.k8s.allow-plaintext-credentials:false}") boolean allowPlaintext) {
        if (base64Key == null || base64Key.isBlank()) {
            if (!allowPlaintext) {
                throw new IllegalStateException("ingestion.k8s.credentials-key (CLUSTER_CREDENTIALS_KEY) is not set. "
                        + "Set a Base64 AES key (openssl rand -base64 32). For local development only, set "
                        + "ingestion.k8s.allow-plaintext-credentials=true to store credentials in plain text.");
            }
            log.warn("ingestion.k8s.credentials-key not set — credentials stored without encryption "
                    + "(allow-plaintext-credentials=true)");
            this.key = null;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                throw new IllegalStateException("ingestion.k8s.credentials-key must decode to 16, 24 or 32 bytes");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
        }
    }

    // Prefix marks values that were encrypted with a key so decrypt can distinguish
    // plaintext rows stored before a key was configured.
    private static final String ENC_PREFIX = "enc:";

    public String encrypt(String plaintext) {
        if (key == null) return plaintext;
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, IV_LENGTH, ciphertext.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt credential", ex);
        }
    }

    public String decrypt(String value) {
        if (value == null) return null;
        // Values stored before a key was configured have no prefix — return as-is.
        if (!value.startsWith(ENC_PREFIX)) return value;
        if (key == null) {
            throw new IllegalStateException("Failed to decrypt credential: value is encrypted but no key is set");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value.substring(ENC_PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(decoded, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(decoded, IV_LENGTH, decoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt credential", ex);
        }
    }
}
