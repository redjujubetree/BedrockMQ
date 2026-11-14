package top.redjujubetree.bedrock.mq.client;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM wire encryption. A fresh 96-bit IV is generated per message. */
public class MessageCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MessageCipher(String base64Key, String algorithm) {
        int keyLength;
        if ("AES-256-GCM".equalsIgnoreCase(algorithm)) {
            keyLength = 32;
        } else if ("AES-128-GCM".equalsIgnoreCase(algorithm)) {
            keyLength = 16;
        } else {
            throw new IllegalArgumentException("Unsupported HTTP encryption algorithm");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("HTTP encryption key must be Base64 encoded", e);
        }
        if (bytes.length != keyLength) {
            throw new IllegalArgumentException("HTTP encryption key length does not match algorithm");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public EncryptedMessage encrypt(String plaintext) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedMessage(Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(encrypted));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to encrypt message", e);
        }
    }

    public String decrypt(EncryptedMessage message) {
        try {
            byte[] iv = Base64.getDecoder().decode(message.getIv());
            byte[] encrypted = Base64.getDecoder().decode(message.getCiphertext());
            if (iv.length != 12 || encrypted.length < 16) {
                throw new IllegalArgumentException("Invalid encrypted message");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid encrypted message", e);
        }
    }
}
