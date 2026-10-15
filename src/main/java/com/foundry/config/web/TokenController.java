package com.foundry.config.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.foundry.config.ConfigProps;
import com.foundry.config.dto.Dtos.TokenRequest;
import com.foundry.config.dto.Dtos.TokenResponse;
import com.foundry.config.security.TokenService;
import com.foundry.config.service.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an application proves who it is (client id and secret, kept on the server side of that application) and gets
 * a short-lived token. The token is then sent as {@code Authorization: Bearer ...} to {@code /config/{app}}.
 */
@RestController
public class TokenController {

    private final ConfigProps props;
    private final TokenService tokens;
    private final LoginThrottle throttle;

    public TokenController(ConfigProps props, TokenService tokens, LoginThrottle throttle) {
        this.props = props;
        this.tokens = tokens;
        this.throttle = throttle;
    }

    @PostMapping("/auth/token")
    public ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest body, HttpServletRequest req) {
        String key = "client:" + LoginThrottle.keyOf(body.clientId(), req.getRemoteAddr());
        throttle.check(key);
        ConfigProps.Client found = null;
        for (ConfigProps.Client c : props.clients()) {
            // Every client is compared, and with a constant-time comparison, so timing reveals nothing.
            boolean idOk = MessageDigest.isEqual(bytes(c.id()), bytes(body.clientId().trim()));
            boolean secretOk = MessageDigest.isEqual(bytes(c.secret()), bytes(body.clientSecret()));
            if (idOk && secretOk) {
                found = c;
            }
        }
        if (found == null) {
            throttle.failed(key);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid client id or secret.");
        }
        throttle.succeeded(key);
        TokenResponse out = new TokenResponse(tokens.issue(found), "Bearer", tokens.ttlSeconds(), found.apps());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    private static byte[] bytes(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}
