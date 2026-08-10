package com.ledgerops.notification.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookSecretCipherTests {

    @Test
    void encryptsAndDecryptsWithTheStoredKeyVersion() {
        String master = Base64.getEncoder().encodeToString(new byte[32]);
        WebhookSecretCipher cipher = new WebhookSecretCipher(master, "v3");

        String plaintext = cipher.generatePlaintextSecret();
        WebhookSecretCipher.EncryptedSecret encrypted = cipher.encrypt(plaintext);

        assertEquals(plaintext, cipher.decrypt(
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion()));
        assertNotEquals(plaintext, Base64.getEncoder().encodeToString(encrypted.ciphertext()));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(
                encrypted.ciphertext(), encrypted.nonce(), "v2"));
    }
}
