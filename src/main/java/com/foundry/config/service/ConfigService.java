package com.foundry.config.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.dto.Dtos.AppsView;
import com.foundry.config.dto.Dtos.EntryRequest;
import com.foundry.config.dto.Dtos.EntryView;
import com.foundry.config.dto.Dtos.PreviewRow;
import com.foundry.config.dto.Dtos.Resolved;
import com.foundry.config.repo.ConfigEntryRepository;
import com.foundry.config.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores settings and merges them for a client.
 *
 * <p>Merge order, lowest to highest priority: shared settings ({@value #SHARED}) before the application's own, and
 * within each of those the {@value #DEFAULT_PROFILE} profile first, then the requested profiles in the order given.
 * A later layer overrides an earlier one for the same key.
 */
@Service
@Transactional
public class ConfigService {

    public static final String SHARED = "application";
    public static final String DEFAULT_PROFILE = "default";
    public static final String UI_APP = "ui";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final ConfigEntryRepository repo;
    private final SecretCrypto crypto;
    private final HistoryService history;

    public ConfigService(ConfigEntryRepository repo, SecretCrypto crypto, HistoryService history) {
        this.repo = repo;
        this.crypto = crypto;
        this.history = history;
    }

    /** The real value of a setting (secrets are stored encrypted). */
    String plain(ConfigEntry e) {
        return e.isSecret() ? crypto.decrypt(e.getPropValue()) : e.getPropValue();
    }

    private String store(boolean secret, String plain) {
        return secret && plain != null && !plain.isEmpty() ? crypto.encrypt(plain) : (plain == null ? "" : plain);
    }

    /* ---------- merging ---------- */

    private record Merged(String value, boolean secret, String source) {
    }

    private Map<String, Merged> merge(List<String> apps, List<String> profiles) {
        List<String> order = new ArrayList<>();
        order.add(DEFAULT_PROFILE);
        profiles.stream().filter(p -> !p.equals(DEFAULT_PROFILE)).forEach(order::add);
        Map<String, Merged> out = new TreeMap<>();
        for (String app : apps) {
            for (String profile : order) {
                for (ConfigEntry e : repo.findByAppNameAndProfileNameOrderByPropKeyAsc(app, profile)) {
                    out.put(e.getPropKey(), new Merged(plain(e), e.isSecret(), app + "/" + profile));
                }
            }
        }
        return out;
    }

    private static List<String> appsFor(String app) {
        return app.equals(SHARED) ? List.of(SHARED) : List.of(SHARED, app);
    }

    /** Everything an application needs, secrets included, with the shared "application" settings underneath. */
    @Transactional(readOnly = true)
    public Resolved resolve(String app, String profilesCsv) {
        return resolve(app, profilesCsv, true);
    }

    /**
     * Settings for one application. Without secrets (an application that is not trusted with them, such as the UI)
     * only the application's own entries are used, never the shared ones, and a secret that wins a key hides that key
     * completely instead of letting an older public value show through.
     */
    @Transactional(readOnly = true)
    public Resolved resolve(String app, String profilesCsv, boolean withSecrets) {
        String a = checkName(app, "Application");
        List<String> profiles = parseProfiles(profilesCsv);
        Map<String, String> props = new LinkedHashMap<>();
        merge(withSecrets ? appsFor(a) : List.of(a), profiles).forEach((k, v) -> {
            if (withSecrets || !v.secret()) {
                props.put(k, v.value());
            }
        });
        return new Resolved(a, profiles, props);
    }

    /** The console's preview: the winning value for each key and which layer it came from. Secrets are masked. */
    @Transactional(readOnly = true)
    public List<PreviewRow> preview(String app, String profilesCsv) {
        String a = checkName(app, "Application");
        List<PreviewRow> rows = new ArrayList<>();
        merge(appsFor(a), parseProfiles(profilesCsv)).forEach((k, v) ->
            rows.add(new PreviewRow(k, v.secret() ? "" : v.value(), v.secret(), v.source())));
        return rows;
    }

    /** Problems in what the app would receive (see {@link SettingRules#warnings}). */
    @Transactional(readOnly = true)
    public List<String> warnings(String app, String profilesCsv) {
        Map<String, String> m = new TreeMap<>();
        merge(appsFor(checkName(app, "Application")), parseProfiles(profilesCsv)).forEach((k, v) -> m.put(k, v.value()));
        return SettingRules.warnings(m);
    }

    /** How many settings of these applications (and the shared ones) changed after {@code since}. */
    @Transactional(readOnly = true)
    public int changedSince(List<String> apps, Instant since) {
        int n = 0;
        for (ConfigEntry e : repo.findAll()) {
            if ((apps.contains(e.getAppName()) || e.getAppName().equals(SHARED)) && e.getUpdatedAt().isAfter(since)) {
                n++;
            }
        }
        return n;
    }

    /* ---------- editing ---------- */

    @Transactional(readOnly = true)
    public List<EntryView> list(String app, String profile) {
        List<EntryView> out = new ArrayList<>();
        for (ConfigEntry e : repo.findAllByOrderByAppNameAscProfileNameAscPropKeyAsc()) {
            if (app != null && !app.isBlank() && !e.getAppName().equals(app)) {
                continue;
            }
            if (profile != null && !profile.isBlank() && !e.getProfileName().equals(profile)) {
                continue;
            }
            out.add(view(e));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AppsView apps() {
        TreeSet<String> apps = new TreeSet<>(List.of(SHARED, UI_APP));
        TreeSet<String> profiles = new TreeSet<>(List.of(DEFAULT_PROFILE));
        for (ConfigEntry e : repo.findAll()) {
            apps.add(e.getAppName());
            profiles.add(e.getProfileName());
        }
        return new AppsView(new ArrayList<>(apps), new ArrayList<>(profiles));
    }

    public EntryView create(EntryRequest r, String user) {
        String app = checkName(r.app(), "Application");
        String profile = profileOf(r.profile());
        String key = r.key().trim();
        if (repo.findByAppNameAndProfileNameAndPropKey(app, profile, key).isPresent()) {
            throw ApiException.conflict("That setting already exists for this application and profile. Edit it instead.");
        }
        ConfigEntry e = new ConfigEntry();
        e.setAppName(app);
        e.setProfileName(profile);
        e.setPropKey(key);
        String value = r.value() == null ? "" : r.value();
        if (!r.secret()) {
            SettingRules.validate(key, value);
        }
        e.setPropValue(store(r.secret(), value));
        e.setSecret(r.secret());
        e.setDescription(blankToNull(r.description()));
        stamp(e, user);
        ConfigEntry saved = repo.save(e);
        history.record(saved, "CREATE", null, r.secret() ? null : value, user);
        return view(saved);
    }

    /** Checks a setting the way saving would, without saving it (used by the import preview). */
    @Transactional(readOnly = true)
    public void check(EntryRequest r) {
        checkName(r.app(), "Application");
        profileOf(r.profile());
        if (!r.secret()) {
            SettingRules.validate(r.key().trim(), r.value() == null ? "" : r.value());
        }
    }

    @Transactional(readOnly = true)
    public java.util.Optional<EntryView> find(String app, String profile, String key) {
        return repo.findByAppNameAndProfileNameAndPropKey(app, profile, key).map(ConfigService::view);
    }

    /** The real value, for copying a setting to another profile (secrets included: only for admin tools, never sent to a browser). */
    @Transactional(readOnly = true)
    public List<EntryRequest> copies(String app, String fromProfile, String toProfile) {
        List<EntryRequest> out = new ArrayList<>();
        for (ConfigEntry e : repo.findAllByOrderByAppNameAscProfileNameAscPropKeyAsc()) {
            if (e.getProfileName().equals(fromProfile) && (app == null || app.isBlank() || e.getAppName().equals(app))) {
                out.add(new EntryRequest(e.getAppName(), toProfile, e.getPropKey(), plain(e), e.isSecret(), e.getDescription()));
            }
        }
        return out;
    }

    /** Every setting with its real value, for the export file. Secrets only when asked for. */
    @Transactional(readOnly = true)
    public List<com.foundry.config.dto.Dtos.ImportEntry> exportAll(String app, String profile, boolean withSecrets) {
        List<com.foundry.config.dto.Dtos.ImportEntry> out = new ArrayList<>();
        for (ConfigEntry e : repo.findAllByOrderByAppNameAscProfileNameAscPropKeyAsc()) {
            if ((app != null && !app.isBlank() && !e.getAppName().equals(app)) || (profile != null && !profile.isBlank() && !e.getProfileName().equals(profile))) {
                continue;
            }
            out.add(new com.foundry.config.dto.Dtos.ImportEntry(e.getAppName(), e.getProfileName(), e.getPropKey(),
                e.isSecret() && !withSecrets ? null : plain(e), e.isSecret(), e.getDescription()));
        }
        return out;
    }

    /** Puts a setting back the way a history entry says it was before the change. Not possible for secrets. */
    public EntryView undo(Long historyId, String user) {
        HistoryService.Row h = history.find(historyId).orElseThrow(() -> ApiException.notFound("History entry"));
        if (h.secret()) {
            throw ApiException.badRequest("Changes to secret settings are not kept, so they cannot be undone.");
        }
        var current = repo.findByAppNameAndProfileNameAndPropKey(h.app(), h.profile(), h.key());
        switch (h.action()) {
            case "CREATE" -> {
                ConfigEntry e = current.orElseThrow(() -> ApiException.conflict("That setting no longer exists."));
                history.record(e, "DELETE", h.newValue(), null, user);
                repo.delete(e);
                return view(e);
            }
            case "DELETE" -> {
                if (current.isPresent()) {
                    throw ApiException.conflict("That setting exists again; edit it instead.");
                }
                return create(new EntryRequest(h.app(), h.profile(), h.key(), h.oldValue(), false, null), user);
            }
            default -> {
                ConfigEntry e = current.orElseThrow(() -> ApiException.conflict("That setting no longer exists."));
                return update(e.getId(), new EntryRequest(h.app(), h.profile(), h.key(), h.oldValue(), false, e.getDescription()), user);
            }
        }
    }

    public EntryView update(Long id, EntryRequest r, String user) {
        ConfigEntry e = repo.findById(id).orElseThrow(() -> ApiException.notFound("Setting"));
        String app = checkName(r.app(), "Application");
        String profile = profileOf(r.profile());
        String key = r.key().trim();
        boolean moved = !e.getAppName().equals(app) || !e.getProfileName().equals(profile) || !e.getPropKey().equals(key);
        if (moved && repo.findByAppNameAndProfileNameAndPropKey(app, profile, key).isPresent()) {
            throw ApiException.conflict("That setting already exists for this application and profile.");
        }
        // The console never receives a secret's value, so a missing value means "keep the stored one".
        if (r.value() == null && e.isSecret() && !r.secret()) {
            throw ApiException.badRequest("Enter a value when you stop marking a setting as secret.");
        }
        String before = plain(e);
        boolean wasSecret = e.isSecret();
        String next = r.value() != null ? r.value() : before;
        if (!r.secret()) {
            SettingRules.validate(key, next);
        }
        e.setPropValue(store(r.secret(), next));
        e.setAppName(app);
        e.setProfileName(profile);
        e.setPropKey(key);
        e.setSecret(r.secret());
        e.setDescription(blankToNull(r.description()));
        stamp(e, user);
        ConfigEntry saved = repo.save(e);
        if (wasSecret || r.secret() || !before.equals(next) || moved) {
            history.record(saved, "UPDATE", wasSecret || r.secret() ? null : before, wasSecret || r.secret() ? null : next, user);
        }
        return view(saved);
    }

    public void delete(Long id, String user) {
        ConfigEntry e = repo.findById(id).orElseThrow(() -> ApiException.notFound("Setting"));
        history.record(e, "DELETE", e.isSecret() ? null : plain(e), null, user);
        repo.delete(e);
    }

    /* ---------- helpers ---------- */

    private static void stamp(ConfigEntry e, String user) {
        e.setUpdatedAt(Instant.now());
        e.setUpdatedBy(user);
    }

    private static EntryView view(ConfigEntry e) {
        return new EntryView(e.getId(), e.getAppName(), e.getProfileName(), e.getPropKey(),
            e.isSecret() ? "" : e.getPropValue(), e.isSecret(), e.getDescription(), e.getUpdatedAt(), e.getUpdatedBy(),
            SettingRules.group(e.getAppName(), e.getPropKey()), SettingRules.hint(e.getPropKey()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String profileOf(String p) {
        return p == null || p.isBlank() ? DEFAULT_PROFILE : checkName(p, "Profile");
    }

    private static String checkName(String name, String what) {
        String n = name == null ? "" : name.trim();
        if (!NAME.matcher(n).matches()) {
            throw ApiException.badRequest(what + " may only use letters, numbers, - and _.");
        }
        return n;
    }

    static List<String> parseProfiles(String csv) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String p : csv.split(",")) {
                if (!p.isBlank()) {
                    out.add(checkName(p, "Profile"));
                }
            }
        }
        return new ArrayList<>(out);
    }
}
