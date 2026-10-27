package com.pombo.shared;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifra os segredos de webhook em repouso (AES-256-GCM).
 * Formato guardado no banco: nonce (12 bytes) + ciphertext + tag.
 */
@Component
public class SecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String base64Key;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec key;

    public SecretCipher(@Value("${pombo.security.secrets-enc-key}") String base64Key) {
        this.base64Key = base64Key;
    }

    @PostConstruct
    void init() {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        if (raw.length != 32) {
            throw new IllegalStateException("SECRETS_ENC_KEY deve ter 32 bytes em Base64 (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar segredo", e);
        }
    }

    public String decrypt(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar segredo", e);
        }
    }
}