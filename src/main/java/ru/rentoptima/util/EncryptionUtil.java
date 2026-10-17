package ru.rentoptima.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM шифрование чувствительных значений (токены, API-ключи).
 * Ключ шифрования подаётся через env-переменную ENCRYPTION_KEY (base64, 32 байта).
 * <p>
 * Формат шифротекста: base64(IV || ciphertext || tag), где:
 * - IV — 12 байт (случайный, генерируется на каждое шифрование)
 * - GCM tag — 16 байт (в конце ciphertext'а)
 */
@Slf4j
@Component
public class EncryptionUtil {

    private static final String ALGO = "AES";
    private static final String CIPHER_TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public EncryptionUtil(@Value("${encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("ENCRYPTION_KEY not set — sensitive values will be stored as-is (INSECURE, dev-only)");
            this.keySpec = null;
        } else {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length != 32) {
                throw new IllegalStateException(
                        "ENCRYPTION_KEY must be 32 bytes base64-encoded (got " + decoded.length + ")");
            }
            this.keySpec = new SecretKeySpec(decoded, ALGO);
        }
    }

    /** Возвращает зашифрованное значение или plain-text если ключ не настроен. */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (keySpec == null) return plaintext; // dev fallback

        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /** Расшифровывает или возвращает как есть если ключ не настроен. */
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return encoded;
        if (keySpec == null) return encoded; // dev fallback

        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            byte[] cipherText = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(cipherText);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public boolean isEnabled() {
        return keySpec != null;
    }
}
