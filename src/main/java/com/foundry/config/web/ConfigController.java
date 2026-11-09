package com.foundry.config.web;

import com.foundry.config.dto.Dtos.Resolved;
import com.foundry.config.security.TokenFilter;
import com.foundry.config.service.ConfigService;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What other applications read: {@code GET /config/{app}?profiles=a,b} with a token from {@code POST /auth/token}.
 * The token decides which applications may be read and whether secrets are included.
 */
@RestController
public class ConfigController {

    private final ConfigService service;

    private final com.foundry.config.service.ClientService clients;

    public ConfigController(ConfigService service, com.foundry.config.service.ClientService clients) {
        this.clients = clients;
        this.service = service;
    }

    @GetMapping("/config/{app}")
    public ResponseEntity<Resolved> forApp(@PathVariable String app, @RequestParam(required = false) String profiles,
                                           Authentication auth) {
        boolean secrets = auth.getAuthorities().stream().anyMatch(a -> TokenFilter.SECRETS.equals(a.getAuthority()));
        clients.fetched(auth.getName(), app);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(app, profiles, secrets));
    }
}
