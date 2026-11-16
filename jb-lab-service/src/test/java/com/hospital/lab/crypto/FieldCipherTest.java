package com.hospital.lab.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCipherTest {

    private static final String K1 = key("key-one-key-one-key-one-key-one!");
    private static final String K2 = key("key-two-key-two-key-two-key-two!");

    private final FieldCipher cipher = new FieldCipher("k1:" + K1, "k1", false);

    @Test
    void roundTripsUnicodeText() {
        String stored = cipher.encrypt("Diagnosis: टाइफाइड (A01.0), allergic to penicillin");

        assertThat(stored).startsWith("enc:k1:").doesNotContain("penicillin");
        assertThat(cipher.decrypt(stored)).isEqualTo("Diagnosis: टाइफाइड (A01.0), allergic to penicillin");
    }

    @Test
    void theSameTextEncryptsDifferentlyEachTime() {
        assertThat(cipher.encrypt("Fever")).isNotEqualTo(cipher.encrypt("Fever"));
    }

    @Test
    void nullStaysNull() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void aTamperedValueIsRejected() {
        String stored = cipher.encrypt("Fever");
        char last = stored.charAt(stored.length() - 3);
        String tampered = stored.substring(0, stored.length() - 3) + (last == 'A' ? 'B' : 'A') + stored.substring(stored.length() - 2);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("Fever");
    }

    @Test
    void movingAValueUnderAnotherKeyIdIsRejected() {
        FieldCipher twoKeys = new FieldCipher("k1:" + K1 + ",k2:" + K1, "k1", false);   // same bytes, different id
        String stored = twoKeys.encrypt("Fever");

        assertThatThrownBy(() -> twoKeys.decrypt(stored.replace("enc:k1:", "enc:k2:")))
                .isInstanceOf(IllegalStateException.class);   // key id is authenticated
    }

    @Test
    void plaintextIsRefusedUnlessMigrating() {
        assertThatThrownBy(() -> cipher.decrypt("legacy plain value")).isInstanceOf(IllegalStateException.class);

        FieldCipher migrating = new FieldCipher("k1:" + K1, "k1", true);
        assertThat(migrating.decrypt("legacy plain value")).isEqualTo("legacy plain value");
    }

    @Test
    void rotationReadsOldValuesAndWritesWithTheNewKey() {
        String underK1 = cipher.encrypt("Fever");
        FieldCipher rotated = new FieldCipher("k1:" + K1 + ",k2:" + K2, "k2", false);

        assertThat(rotated.decrypt(underK1)).isEqualTo("Fever");
        assertThat(rotated.needsRewrap(underK1)).isTrue();
        String underK2 = rotated.encrypt("Fever");
        assertThat(underK2).startsWith("enc:k2:");
        assertThat(rotated.needsRewrap(underK2)).isFalse();
        assertThat(rotated.needsRewrap("plain")).isTrue();
    }

    @Test
    void anUnknownKeyIdIsRejected() {
        FieldCipher other = new FieldCipher("k9:" + K2, "k9", false);

        assertThatThrownBy(() -> cipher.decrypt(other.encrypt("Fever")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("k9");
    }

    @Test
    void badKeyConfigurationFailsAtStartup() {
        assertThatThrownBy(() -> new FieldCipher("", "k1", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldCipher("k1:" + key("too-short"), "k1", false))
                .hasMessageContaining("256-bit");
        assertThatThrownBy(() -> new FieldCipher("k1:not base64!!", "k1", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new FieldCipher("k1:" + K1, "k2", false)).hasMessageContaining("active-key");
        assertThatThrownBy(() -> new FieldCipher("missing-colon", "k1", false)).isInstanceOf(IllegalStateException.class);
    }

    private static String key(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
