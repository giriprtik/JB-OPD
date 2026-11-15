package com.hospital.lab.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-256-GCM for clinical free-text columns.
 *
 * Stored form: {@code enc:<keyId>:<base64(iv || ciphertext+tag)>}. The key id is authenticated (AAD), so a value
 * can't be moved under another key. Several keys can be configured for rotation; new writes use the active one.
 *
 * Keys come from {@code app.crypto.keys} as {@code id:base64key,id2:base64key2}; generate each with
 * {@code openssl rand -base64 32}. Losing a key means losing the data written with it: keep an offline copy.
 */
@Component
public class FieldCipher {

    public static final String PREFIX = "enc:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final boolean allowPlaintext;

    @Autowired
    public FieldCipher(@Value("${app.crypto.keys}") String keySpec,
                       @Value("${app.crypto.active-key}") String activeKeyId,
                       @Value("${app.crypto.allow-plaintext:false}") boolean allowPlaintext) {
        this.keys = parseKeys(keySpec);
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalStateException("app.crypto.active-key '" + activeKeyId + "' is not in app.crypto.keys");
        }
        this.activeKeyId = activeKeyId;
        this.allowPlaintext = allowPlaintext;
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null) return null;
        if (!isEncrypted(stored)) {
            if (allowPlaintext) return stored;   // legacy row, before the backfill ran
            throw new IllegalStateException("Unencrypted value found in an encrypted column");
        }
        String[] parts = stored.split(":", 3);
        if (parts.length != 3) throw new IllegalStateException("Malformed encrypted value");
        SecretKey key = keys.get(parts[1]);
        if (key == null) throw new IllegalStateException("Unknown encryption key id '" + parts[1] + "'");
        try {
            byte[] all = Base64.getDecoder().decode(parts[2]);
            if (all.length <= IV_BYTES) throw new IllegalStateException("Malformed encrypted value");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(parts[1].getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Never include the value in the message: it may be logged.
            throw new IllegalStateException("Field decryption failed (tampered value or wrong key)", e);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** True for plaintext or for values under an old key: what the backfill/rotation job rewrites. */
    public boolean needsRewrap(String stored) {
        return stored != null && !stored.startsWith(PREFIX + activeKeyId + ":");
    }

    private static Map<String, SecretKey> parseKeys(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalStateException("app.crypto.keys is not set");
        Map<String, SecretKey> parsed = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String[] idAndKey = entry.trim().split(":", 2);
            if (idAndKey.length != 2 || idAndKey[0].isBlank()) {
                throw new IllegalStateException("app.crypto.keys entries must look like id:base64key");
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(idAndKey[1].trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Key '" + idAndKey[0] + "' is not valid base64", e);
            }
            if (raw.length != 32) {
                throw new IllegalStateException("Key '" + idAndKey[0] + "' must be 256-bit (32 bytes), got " + raw.length);
            }
            parsed.put(idAndKey[0].trim(), new SecretKeySpec(raw, "AES"));
        }
        return Map.copyOf(parsed);
    }
}
