package com.foundry.config.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foundry.config.ConfigProps;
import org.springframework.stereotype.Service;

/**
 * "Refresh now": asks each application that has a {@code refresh-url} to read its settings again straight away, instead of waiting for its
 * next scheduled refresh (the backend does it every 5 minutes). The call carries the client's own secret as a Bearer token, which the
 * application compares with the one it already has.
 */
@Service
public class RefreshService {

    private final ConfigProps props;
    private final ClientService clients;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public RefreshService(ConfigProps props, ClientService clients) {
        this.props = props;
        this.clients = clients;
    }

    public List<Map<String, Object>> refreshAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigProps.Client c : props.clients()) {
            if (c.refreshUrl() == null || c.refreshUrl().isBlank()) {
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("client", c.id());
            if (clients.isDisabled(c.id())) {
                r.put("ok", false);
                r.put("message", "Switched off.");
                out.add(r);
                continue;
            }
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(c.refreshUrl())).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + c.secret()).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                r.put("ok", res.statusCode() / 100 == 2);
                r.put("message", res.statusCode() / 100 == 2 ? "Refreshed." : "The application answered " + res.statusCode() + ".");
            } catch (Exception e) {
                r.put("ok", false);
                r.put("message", "Could not reach it: " + e.getClass().getSimpleName());
            }
            out.add(r);
        }
        return out;
    }
}
