package com.labor.sync.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoServiceTest {
    private final CryptoService crypto = new CryptoService("test-encryption", "test-hash", "legacy-encryption");

    @Test
    void encryptsAndHashesSensitiveValues() {
        String encrypted = crypto.encrypt("110101199001011234");
        assertThat(encrypted).doesNotContain("110101");
        assertThat(crypto.decrypt(encrypted)).isEqualTo("110101199001011234");
        assertThat(crypto.hmac("110101199001011234")).hasSize(64);
    }

    @Test
    void decryptsValuesWrittenWithLegacyKey() {
        CryptoService legacy = new CryptoService("legacy-encryption", "test-hash", "");
        assertThat(crypto.decrypt(legacy.encrypt("110101199001011234"))).isEqualTo("110101199001011234");
    }
}
