package com.foundry.config.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.foundry.config.dto.Dtos.EntryRequest;
import com.foundry.config.dto.Dtos.ImportEntry;
import com.foundry.config.web.ApiException;
import org.springframework.stereotype.Service;

/** Export, import and "copy this profile to another" (for example production to staging). Each setting is saved on its own, so one bad row never blocks the rest. */
@Service
public class TransferService {

    private final ConfigService configs;

    public TransferService(ConfigService configs) {
        this.configs = configs;
    }

    private static String profileOf(String p) {
        return p == null || p.isBlank() ? ConfigService.DEFAULT_PROFILE : p.trim();
    }

    /**
     * Applies (or, with {@code dryRun}, only checks) a list of settings. An existing setting is left alone unless {@code overwrite};
     * a secret without a value keeps the stored one.
     */
    public Map<String, Object> apply(List<EntryRequest> entries, boolean overwrite, boolean dryRun, String user) {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        int line = 0;
        for (EntryRequest r : entries) {
            line++;
            try {
                if (r.app() == null || r.app().isBlank() || r.key() == null || r.key().isBlank()) {
                    throw ApiException.badRequest("Application and key are required.");
                }
                var existing = configs.find(r.app().trim(), profileOf(r.profile()), r.key().trim());
                if (existing.isPresent() && !overwrite) {
                    skipped++;
                    continue;
                }
                if (existing.isEmpty() && r.secret() && (r.value() == null || r.value().isEmpty())) {
                    throw ApiException.badRequest("A new secret needs a value.");
                }
                if (existing.isPresent() && !r.secret() && r.value() == null) {
                    throw ApiException.badRequest("A value is needed.");
                }
                configs.check(r);
                if (dryRun) {
                    if (existing.isPresent()) {
                        updated++;
                    } else {
                        created++;
                    }
                    continue;
                }
                if (existing.isPresent()) {
                    configs.update(existing.get().id(), r, user);
                    updated++;
                } else {
                    configs.create(r, user);
                    created++;
                }
            } catch (ApiException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("line", line);
                err.put("key", r.key());
                err.put("message", e.getMessage());
                errors.add(err);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("created", created);
        out.put("updated", updated);
        out.put("skipped", skipped);
        out.put("errors", errors);
        return out;
    }

    public Map<String, Object> importEntries(List<ImportEntry> entries, boolean overwrite, boolean dryRun, String user) {
        if (entries == null || entries.isEmpty()) {
            throw ApiException.badRequest("The file has no settings.");
        }
        if (entries.size() > 2000) {
            throw ApiException.badRequest("At most 2000 settings at a time.");
        }
        List<EntryRequest> list = new ArrayList<>();
        for (ImportEntry e : entries) {
            list.add(new EntryRequest(e.app(), e.profile(), e.key(), e.value(), e.secret(), e.description()));
        }
        return apply(list, overwrite, dryRun, user);
    }

    public Map<String, Object> clone(String app, String from, String to, boolean overwrite, String user) {
        String f = profileOf(from);
        String t = profileOf(to);
        if (f.equals(t)) {
            throw ApiException.badRequest("Choose two different profiles.");
        }
        List<EntryRequest> copies = configs.copies(app, f, t);
        if (copies.isEmpty()) {
            throw ApiException.badRequest("Profile " + f + " has no settings to copy.");
        }
        return apply(copies, overwrite, false, user);
    }
}
