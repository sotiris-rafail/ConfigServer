package com.foundry.config.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Two separate doors:
 * <ul>
 *   <li>{@code /config/**}: for applications. A Bearer token issued by {@code POST /auth/token}, no session.</li>
 *   <li>everything else: the console. The admin logs in with JSON and gets a session cookie.
 *       {@code POST /auth/token} is open: it checks the client id and secret itself.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Nobody signs in with a Spring user: the admin goes through AuthController and applications use tokens. */
    @Bean
    public UserDetailsService noDefaultUser() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    @Order(1)
    public SecurityFilterChain clientChain(HttpSecurity http, TokenService tokens) throws Exception {
        http
            .securityMatcher("/config/**")
            .csrf(csrf -> csrf.disable()) // read-only, no cookies, no session
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new TokenFilter(tokens), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("CLIENT"))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "A token is required. Get one from POST /auth/token."))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "You do not have access to that.")))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain consoleChain(HttpSecurity http, SecurityContextRepository repo) throws Exception {
        http
            // The cookie is SameSite=Lax and the API only accepts JSON. Turn CSRF protection on before exposing
            // the console to the internet.
            .csrf(csrf -> csrf.disable())
            .securityContext(sc -> sc.securityContextRepository(repo))
            .sessionManagement(s -> s
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(f -> f.changeSessionId()))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> json(res, 401, "Please log in to continue."))
                .accessDeniedHandler((req, res, ex) -> json(res, 403, "You do not have access to that.")))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable());
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
