package com.foundry.config.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import com.foundry.config.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Slows down password guessing: after a few wrong tries for one username and address, logins pause for a while. */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(5);

    private record Attempt(int failures, Instant lockedUntil) {
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    public static String keyOf(String username, String address) {
        return (username == null ? "" : username.trim().toLowerCase()) + "|" + address;
    }

    /** Throws 429 while the key is locked. */
    public void check(String key) {
        Attempt a = attempts.get(key);
        if (a != null && a.lockedUntil() != null) {
            Instant now = clock.instant();
            if (now.isBefore(a.lockedUntil())) {
                long mins = Math.max(1, Duration.between(now, a.lockedUntil()).toMinutes() + 1);
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many wrong attempts. Try again in about " + mins + " minute" + (mins == 1 ? "" : "s") + ".");
            }
            attempts.remove(key);
        }
    }

    public void failed(String key) {
        attempts.merge(key, new Attempt(1, null), (old, one) -> {
            int n = old.failures() + 1;
            return new Attempt(n, n >= MAX_FAILURES ? clock.instant().plus(LOCK) : null);
        });
        if (attempts.size() > 10_000) {
            Instant now = clock.instant();
            attempts.entrySet().removeIf(e -> e.getValue().lockedUntil() != null && now.isAfter(e.getValue().lockedUntil()));
        }
    }

    public void succeeded(String key) {
        attempts.remove(key);
    }
}
