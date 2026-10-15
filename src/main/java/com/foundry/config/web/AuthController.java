package com.foundry.config.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import com.foundry.config.ConfigProps;
import com.foundry.config.dto.Dtos.LoginRequest;
import com.foundry.config.dto.Dtos.Me;
import com.foundry.config.service.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ConfigProps props;
    private final PasswordEncoder encoder;
    private final String adminHash;
    private final SecurityContextRepository contextRepository;
    private final LoginThrottle throttle;

    public AuthController(ConfigProps props, PasswordEncoder encoder, SecurityContextRepository contextRepository,
                          LoginThrottle throttle) {
        this.props = props;
        this.encoder = encoder;
        this.adminHash = encoder.encode(props.admin().password());
        this.contextRepository = contextRepository;
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String key = LoginThrottle.keyOf(body.username(), req.getRemoteAddr());
        System.out.println(props.admin().username());
        throttle.check(key);
        // Both checks always run, so a wrong username and a wrong password take the same time and give the same answer.
        boolean userOk = MessageDigest.isEqual(
            body.username().trim().getBytes(StandardCharsets.UTF_8), props.admin().username().getBytes(StandardCharsets.UTF_8));
        boolean passOk = encoder.matches(body.password(), adminHash);
        if (!userOk || !passOk) {
            throttle.failed(key);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "That username and password do not match.");
        }
        throttle.succeeded(key);
        if (req.getSession(false) != null) {
            req.changeSessionId();
        }
        var token = UsernamePasswordAuthenticationToken.authenticated(props.admin().username(), null,
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);
        return new Me(props.admin().username());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public Me me(Authentication auth) {
        return new Me(auth.getName());
    }
}
