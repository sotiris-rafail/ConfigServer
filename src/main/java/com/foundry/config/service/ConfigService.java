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

    public ConfigService(ConfigEntryRepository repo) {
        this.repo = repo;
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
                    out.put(e.getPropKey(), new Merged(e.getPropValue(), e.isSecret(), app + "/" + profile));
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
        e.setPropValue(r.value());
        e.setSecret(r.secret());
        e.setDescription(blankToNull(r.description()));
        stamp(e, user);
        return view(repo.save(e));
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
        if (r.value() == null) {
            if (e.isSecret() && !r.secret()) {
                throw ApiException.badRequest("Enter a value when you stop marking a setting as secret.");
            }
        } else {
            e.setPropValue(r.value());
        }
        e.setAppName(app);
        e.setProfileName(profile);
        e.setPropKey(key);
        e.setSecret(r.secret());
        e.setDescription(blankToNull(r.description()));
        stamp(e, user);
        return view(repo.save(e));
    }

    public void delete(Long id) {
        ConfigEntry e = repo.findById(id).orElseThrow(() -> ApiException.notFound("Setting"));
        repo.delete(e);
    }

    /* ---------- helpers ---------- */

    private static void stamp(ConfigEntry e, String user) {
        e.setUpdatedAt(Instant.now());
        e.setUpdatedBy(user);
    }

    private static EntryView view(ConfigEntry e) {
        return new EntryView(e.getId(), e.getAppName(), e.getProfileName(), e.getPropKey(),
            e.isSecret() ? "" : e.getPropValue(), e.isSecret(), e.getDescription(), e.getUpdatedAt(), e.getUpdatedBy());
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
