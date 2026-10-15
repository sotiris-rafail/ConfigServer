package com.foundry.config.security;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code /config/{app}}: the request needs {@code Authorization: Bearer <token>} with a token this server
 * issued, and the token must list {@code app}. It is created by the security chain (not a Spring bean), so it only
 * runs for the paths that chain covers.
 */
public class TokenFilter extends OncePerRequestFilter {

    public static final String SECRETS = "SECRETS";

    private final TokenService tokens;

    public TokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
        throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            reject(res, 401, "A token is required. Get one from POST /auth/token.");
            return;
        }
        Optional<TokenService.Claims> claims = tokens.verify(header.substring(7).trim());
        if (claims.isEmpty()) {
            reject(res, 401, "The token is not valid or has expired. Get a new one from POST /auth/token.");
            return;
        }
        String app = appOf(req.getRequestURI());
        if (!claims.get().apps().contains(app)) {
            reject(res, 403, "This token is not allowed to read that application.");
            return;
        }
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_CLIENT"));
        if (claims.get().secrets()) {
            authorities.add(new SimpleGrantedAuthority(SECRETS));
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(claims.get().clientId(), null, authorities));
        SecurityContextHolder.setContext(context);
        chain.doFilter(req, res);
    }

    /** The application name in /config/{app}. */
    static String appOf(String uri) {
        String rest = uri.startsWith("/config/") ? uri.substring("/config/".length()) : "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        return URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }

    private static void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
