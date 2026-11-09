package com.foundry.config.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.foundry.config.ConfigProps;
import com.foundry.config.dto.Dtos.ClientView;
import com.foundry.config.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * What we know about each application that reads settings: when it last got a token and last read its settings, whether any change is
 * still waiting for it, and whether it is switched off (a switched-off client gets no new tokens and its current token stops working
 * at once, without a restart).
 */
@Service
public class ClientService {

    private final ConfigProps props;
    private final JdbcTemplate jdbc;
    private final ConfigService configs;
    private final Map<String, Boolean> disabled = new ConcurrentHashMap<>();

    public ClientService(ConfigProps props, JdbcTemplate jdbc, ConfigService configs) {
        this.props = props;
        this.jdbc = jdbc;
        this.configs = configs;
    }

    private void ensureRow(String id) {
        if (jdbc.update("update client_state set client_id = client_id where client_id = ?", id) == 0) {
            jdbc.update("insert into client_state (client_id, disabled) values (?, false)", id);
        }
    }

    public boolean isDisabled(String clientId) {
        return disabled.computeIfAbsent(clientId, id -> {
            var r = jdbc.queryForList("select disabled from client_state where client_id = ?", Boolean.class, id);
            return !r.isEmpty() && Boolean.TRUE.equals(r.get(0));
        });
    }

    public void tokenIssued(String clientId) {
        ensureRow(clientId);
        jdbc.update("update client_state set last_token_at = ? where client_id = ?", Timestamp.from(Instant.now()), clientId);
    }

    /** Notes that the client read its settings (written at most every 20 seconds per client). */
    public void fetched(String clientId, String app) {
        ensureRow(clientId);
        Instant now = Instant.now();
        jdbc.update("update client_state set last_fetch_at = ?, last_fetch_app = ? where client_id = ? and (last_fetch_at is null or last_fetch_at < ?)",
            Timestamp.from(now), app, clientId, Timestamp.from(now.minusSeconds(20)));
    }

    public void setDisabled(String clientId, boolean off) {
        if (props.clients().stream().noneMatch(c -> c.id().equals(clientId))) {
            throw ApiException.notFound("Client");
        }
        ensureRow(clientId);
        jdbc.update("update client_state set disabled = ? where client_id = ?", off, clientId);
        disabled.put(clientId, off);
    }

    public List<ClientView> list() {
        List<ClientView> out = new ArrayList<>();
        for (ConfigProps.Client c : props.clients()) {
            var rows = jdbc.queryForList("select last_token_at, last_fetch_at, last_fetch_app from client_state where client_id = ?", c.id());
            Instant token = null;
            Instant fetch = null;
            String app = null;
            if (!rows.isEmpty()) {
                var r = rows.get(0);
                token = r.get("last_token_at") == null ? null : ((Timestamp) r.get("last_token_at")).toInstant();
                fetch = r.get("last_fetch_at") == null ? null : ((Timestamp) r.get("last_fetch_at")).toInstant();
                app = (String) r.get("last_fetch_app");
            }
            Integer pending = fetch == null ? null : configs.changedSince(c.apps(), fetch);
            out.add(new ClientView(c.id(), c.apps(), c.secrets(), isDisabled(c.id()), c.refreshUrl() != null && !c.refreshUrl().isBlank(), token, fetch, app, pending));
        }
        return out;
    }
}
