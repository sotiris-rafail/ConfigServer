package com.foundry.config.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.dto.Dtos.HistoryView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Who changed which setting, when, and from what to what. Secret values are never written here. */
@Service
public class HistoryService {

    public record Row(Long id, String app, String profile, String key, String action, String oldValue, String newValue, boolean secret, Instant at, String by) {
    }

    private final JdbcTemplate jdbc;

    public HistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(ConfigEntry e, String action, String oldValue, String newValue, String user) {
        jdbc.update("insert into config_history (entry_id, app_name, profile_name, prop_key, action, old_value, new_value, secret, changed_at, changed_by) values (?,?,?,?,?,?,?,?,?,?)",
            e.getId(), e.getAppName(), e.getProfileName(), e.getPropKey(), action, oldValue, newValue, e.isSecret(), Timestamp.from(Instant.now()), user);
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("id"), rs.getString("app_name"), rs.getString("profile_name"), rs.getString("prop_key"), rs.getString("action"),
            rs.getString("old_value"), rs.getString("new_value"), rs.getBoolean("secret"), rs.getTimestamp("changed_at").toInstant(), rs.getString("changed_by"));
    }

    public Optional<Row> find(Long id) {
        return jdbc.query("select * from config_history where id = ?", (rs, n) -> row(rs), id).stream().findFirst();
    }

    public List<HistoryView> latest(String app, String key, int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("select * from config_history where 1 = 1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (app != null && !app.isBlank()) {
            sql.append(" and app_name = ?");
            args.add(app);
        }
        if (key != null && !key.isBlank()) {
            sql.append(" and prop_key like ?");
            args.add("%" + key.trim() + "%");
        }
        sql.append(" order by id desc limit ").append(n);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Row r = row(rs);
            return new HistoryView(r.id(), r.app(), r.profile(), r.key(), r.action(), r.oldValue(), r.newValue(), r.secret(), r.at(), r.by(), !r.secret());
        }, args.toArray());
    }
}
