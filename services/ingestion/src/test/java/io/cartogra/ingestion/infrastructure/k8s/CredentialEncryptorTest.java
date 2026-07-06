package io.cartogra.ingestion.infrastructure.k8s;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialEncryptorTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]); // 256-bit zero key for tests

    @Test
    void encryptDecrypt_roundtrip() {
        CredentialEncryptor encryptor = new CredentialEncryptor(VALID_KEY);
        String plaintext = "my-secret-token";

        String encrypted = encryptor.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);

        String decrypted = encryptor.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        CredentialEncryptor encryptor = new CredentialEncryptor(VALID_KEY);
        String plaintext = "same-token";

        String first = encryptor.encrypt(plaintext);
        String second = encryptor.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void encryptDecrypt_withEmptyKey_passthroughWithoutEncryption() {
        CredentialEncryptor encryptor = new CredentialEncryptor("");
        String plaintext = "my-token";

        assertThat(encryptor.encrypt(plaintext)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(plaintext)).isEqualTo(plaintext);
    }

    @Test
    void encryptDecrypt_withNullKey_passthroughWithoutEncryption() {
        CredentialEncryptor encryptor = new CredentialEncryptor(null);
        String plaintext = "token";

        assertThat(encryptor.encrypt(plaintext)).isEqualTo(plaintext);
        assertThat(encryptor.decrypt(plaintext)).isEqualTo(plaintext);
    }

    @Test
    void decrypt_withWrongKey_throws() {
        CredentialEncryptor encryptor = new CredentialEncryptor(VALID_KEY);
        String encrypted = encryptor.encrypt("secret");

        byte[] otherKeyBytes = new byte[32];
        otherKeyBytes[0] = 1;
        CredentialEncryptor other = new CredentialEncryptor(Base64.getEncoder().encodeToString(otherKeyBytes));

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    void decrypt_plaintextStoredBeforeKeyWasConfigured_returnedAsIs() {
        CredentialEncryptor encryptor = new CredentialEncryptor(VALID_KEY);
        // Simulates a row written when no key was set (no "enc:" prefix)
        assertThat(encryptor.decrypt("raw-plaintext-token")).isEqualTo("raw-plaintext-token");
    }

    @Test
    void encryptDecrypt_longValue() {
        CredentialEncryptor encryptor = new CredentialEncryptor(VALID_KEY);
        String pem = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(1000) + "\n-----END CERTIFICATE-----";

        assertThat(encryptor.decrypt(encryptor.encrypt(pem))).isEqualTo(pem);
    }
}
