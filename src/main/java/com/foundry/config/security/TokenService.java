package com.foundry.config.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundry.config.ConfigProps;
import org.springframework.stereotype.Component;

/**
 * Makes and checks the access tokens that applications use to read their settings.
 * A token is a standard signed JWT (HS256): header.payload.signature, each part Base64URL. It says which client it
 * was issued to, which applications it may read, whether it may see secrets, and when it ends.
 */
@Component
public class TokenService {

    /** What a valid token says. */
    public record Claims(String clientId, Set<String> apps, boolean secrets) {
    }

    private static final String HEADER = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] key;
    private final long ttlSeconds;
    private final Clock clock;
    private final ObjectMapper json;

    public TokenService(ConfigProps props, Clock clock, ObjectMapper json) {
        String secret = props.token().secret();
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = random;
        } else {
            this.key = secret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = props.token().ttlSeconds() > 0 ? props.token().ttlSeconds() : 900;
        this.clock = clock;
        this.json = json;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(ConfigProps.Client client) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", client.id());
        payload.put("apps", client.apps());
        payload.put("sec", client.secrets());
        payload.put("iat", now);
        payload.put("exp", now + ttlSeconds);
        try {
            String body = HEADER + "." + encode(json.writeValueAsBytes(payload));
            return body + "." + encode(sign(body));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a token", e);
        }
    }

    /** Empty when the token is malformed, was not signed by this server, or has ended. */
    public Optional<Claims> verify(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) {
                return Optional.empty();
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] given = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, given)) {
                return Optional.empty();
            }
            JsonNode p = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            if (p.path("exp").asLong(0) <= clock.instant().getEpochSecond()) {
                return Optional.empty();
            }
            Set<String> apps = new LinkedHashSet<>();
            p.path("apps").forEach(a -> apps.add(a.asText()));
            return Optional.of(new Claims(p.path("sub").asText(), apps, p.path("sec").asBoolean(false)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
