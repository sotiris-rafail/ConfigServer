package com.foundry.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "cfg")
public record ConfigProps(Account admin, @DefaultValue List<Client> clients, @DefaultValue Token token,
                          @DefaultValue("true") boolean seedDefaults) {

    public record Account(String username, String password) {
    }

    /**
     * An application that may ask for a token. {@code apps} are the applications whose settings it may read;
     * {@code secrets} says whether those settings include the ones marked secret.
     */
    public record Client(String id, String secret, @DefaultValue List<String> apps, @DefaultValue("false") boolean secrets) {
    }

    /** {@code secret} signs the tokens (empty: a random one is made at every start, so tokens end with a restart). */
    public record Token(String secret, @DefaultValue("900") long ttlSeconds) {
    }
}
