package com.labor.sync.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class CryptoService {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private final SecretKeySpec encryptionKey;
    private final List<SecretKeySpec> decryptionKeys;
    private final SecretKeySpec hashKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public CryptoService(@Value("${app.crypto.encryption-key}") String encryptionKey,
                         @Value("${app.crypto.hash-key}") String hashKey,
                         @Value("${app.crypto.legacy-encryption-key:}") String legacyEncryptionKey) {
        this.encryptionKey = new SecretKeySpec(sha256(encryptionKey), "AES");
        this.decryptionKeys = new ArrayList<>();
        this.decryptionKeys.add(this.encryptionKey);
        if (legacyEncryptionKey != null && !legacyEncryptionKey.isBlank()
                && !legacyEncryptionKey.equals(encryptionKey)) {
            this.decryptionKeys.add(new SecretKeySpec(sha256(legacyEncryptionKey), "AES"));
        }
        this.hashKey = new SecretKeySpec(hashKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感数据加密失败", ex);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return null;
        Exception lastFailure = null;
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[payload.length - IV_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_BYTES);
            System.arraycopy(payload, IV_BYTES, encrypted, 0, encrypted.length);
            for (SecretKeySpec key : decryptionKeys) {
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                    return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    lastFailure = ex;
                }
            }
        } catch (Exception ex) {
            lastFailure = ex;
        }
        throw new IllegalStateException("敏感数据解密失败", lastFailure);
    }

    public String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(value.trim().toUpperCase().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("敏感数据摘要失败", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
