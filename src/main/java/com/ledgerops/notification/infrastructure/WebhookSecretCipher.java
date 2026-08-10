package com.ledgerops.notification.infrastructure;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class WebhookSecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey masterKey;
    private final String keyVersion;

    public WebhookSecretCipher(String masterKeyBase64, String keyVersion) {
        try {
            byte[] key = Base64.getDecoder().decode(masterKeyBase64);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("Webhook master key must be 128, 192, or 256 bits");
            }
            this.masterKey = new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Webhook master key is not valid Base64 AES key material", exception);
        }
        if (keyVersion == null || keyVersion.isBlank()) {
            throw new IllegalArgumentException("Webhook key version must not be blank");
        }
        this.keyVersion = keyVersion;
    }

    public EncryptedSecret encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Webhook secret must not be blank");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            return new EncryptedSecret(cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    nonce, keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook secret encryption failed", exception);
        }
    }

    public String decrypt(byte[] ciphertext, byte[] nonce, String storedKeyVersion) {
        if (!keyVersion.equals(storedKeyVersion)) {
            throw new IllegalStateException("Webhook secret key version is not available");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Webhook secret decryption failed", exception);
        }
    }

    public String generatePlaintextSecret() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record EncryptedSecret(byte[] ciphertext, byte[] nonce, String keyVersion) {
        public EncryptedSecret {
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }
    }
}
