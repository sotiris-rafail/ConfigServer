package com.foundry.config.service;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.foundry.config.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Two-step sign-in for the console admin: 6-digit codes from an authenticator app (RFC 6238, SHA-1, 30 seconds). The secret is stored encrypted. */
@Service
public class TotpService {

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final JdbcTemplate jdbc;
    private final SecretCrypto crypto;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TotpService(JdbcTemplate jdbc, SecretCrypto crypto, Clock clock) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.clock = clock;
    }

    public boolean enabled(String user) {
        var r = jdbc.queryForList("select enabled from admin_totp where username = ?", Boolean.class, user);
        return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
    }

    /** A new secret (not active until confirmed with a code). */
    public String[] setup(String user) {
        if (enabled(user)) {
            throw ApiException.conflict("Two-step sign-in is already on. Turn it off first to set it up again.");
        }
        byte[] raw = new byte[20];
        random.nextBytes(raw);
        String secret = base32(raw);
        jdbc.update("delete from admin_totp where username = ?", user);
        jdbc.update("insert into admin_totp (username, secret, enabled, last_step) values (?, ?, false, 0)", user, crypto.encrypt(secret));
        String uri = "otpauth://totp/" + URLEncoder.encode("Foundry config:" + user, StandardCharsets.UTF_8).replace("+", "%20")
            + "?secret=" + secret + "&issuer=" + URLEncoder.encode("Foundry config", StandardCharsets.UTF_8).replace("+", "%20");
        return new String[] {secret, uri};
    }

    public void enable(String user, String code) {
        if (!check(user, code, false)) {
            throw ApiException.badRequest("That code is not right. Check the time on your phone and try again.");
        }
        jdbc.update("update admin_totp set enabled = true where username = ?", user);
    }

    public void disable(String user, String code) {
        if (!check(user, code, true)) {
            throw ApiException.badRequest("That code is not right.");
        }
        jdbc.update("delete from admin_totp where username = ?", user);
    }

    /** True when the code is the current one (or one step either side) and was not used before. */
    public boolean verify(String user, String code) {
        return check(user, code, true);
    }

    private boolean check(String user, String code, boolean needEnabled) {
        var rows = jdbc.queryForList("select secret, enabled, last_step from admin_totp where username = ?", user);
        if (rows.isEmpty() || code == null || !code.trim().replace(" ", "").matches("\\d{6}")) {
            return false;
        }
        var r = rows.get(0);
        if (needEnabled && !Boolean.TRUE.equals(r.get("enabled"))) {
            return false;
        }
        byte[] key = unbase32(crypto.decrypt((String) r.get("secret")));
        long last = ((Number) r.get("last_step")).longValue();
        long now = clock.instant().getEpochSecond() / 30;
        String given = code.trim().replace(" ", "");
        for (long step = now - 1; step <= now + 1; step++) {
            if (step > last && java.security.MessageDigest.isEqual(hotp(key, step).getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
                jdbc.update("update admin_totp set last_step = ? where username = ?", step, user); // a code works once
                return true;
            }
        }
        return false;
    }

    private static String hotp(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** For tests: the code that is valid now. */
    public String codeNow(String user) {
        return codeAt(user, 0);
    }

    /** For tests: the code for the step this many 30-second steps from now (the server accepts one step either side). */
    public String codeAt(String user, int stepsFromNow) {
        var s = jdbc.queryForObject("select secret from admin_totp where username = ?", String.class, user);
        return hotp(unbase32(crypto.decrypt(s)), clock.instant().getEpochSecond() / 30 + stepsFromNow);
    }

    private static String base32(byte[] in) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : in) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(B32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(B32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    private static byte[] unbase32(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            int v = B32.indexOf(c);
            if (v < 0) {
                continue;
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
