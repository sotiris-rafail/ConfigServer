package com.foundry.config.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.foundry.config.ConfigProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Encrypts the values of settings marked secret before they are stored (AES-256-GCM, text starting {@code enc1:}), so a copy of the
 * database or of a backup does not reveal them. The key is {@code cfg.crypto.key} (any text; set it from the environment so it lives
 * apart from the database). When it is empty a random key is made once and kept in {@code cfg.crypto.key-file} next to the database,
 * which protects against a leaked database file but not against a leaked disk.
 */
@Component
public class SecretCrypto {

    private static final Logger log = LoggerFactory.getLogger(SecretCrypto.class);
    public static final String PREFIX = "enc1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(ConfigProps props) {
        String configured = props.crypto() == null ? null : props.crypto().key();
        byte[] material;
        if (configured != null && !configured.isBlank()) {
            material = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            material = fromFile(Path.of(props.crypto() == null ? "./data/config.key" : props.crypto().keyFile()));
        }
        this.key = new SecretKeySpec(sha256(material), "AES");
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] fromFile(Path file) {
        try {
            if (Files.exists(file)) {
                return Files.readAllBytes(file);
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            byte[] raw = new byte[48];
            random.nextBytes(raw);
            byte[] text = Base64.getEncoder().encode(raw);
            Files.write(file, text, StandardOpenOption.CREATE_NEW);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException e) {
                // not a POSIX file system
            }
            log.warn("Made a new key for secret settings in {}. Keep a copy of it apart from the database, or set cfg.crypto.key.", file);
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read or create the key file " + file + ": " + e.getMessage(), e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal((plain == null ? "" : plain).getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("A secret setting cannot be decrypted: the encryption key is not the one it was stored with.", e);
        }
    }
}
