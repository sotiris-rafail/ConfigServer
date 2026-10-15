package com.foundry.config.service;

import com.foundry.config.ConfigProps;
import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.repo.ConfigEntryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Fills an empty database with starter settings so the backend and UI work from the first start. */
@Component
public class DefaultsSeeder implements ApplicationRunner {

    private final ConfigEntryRepository repo;
    private final ConfigProps props;

    public DefaultsSeeder(ConfigEntryRepository repo, ConfigProps props) {
        this.repo = repo;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seedDefaults() || repo.count() > 0) {
            return;
        }
        // Settings for the gym backend (keys are its own Spring property names).
        add("backend", "gym.cors-origins", "http://localhost:5173,http://127.0.0.1:5173", false, "Browser addresses allowed to call the API");
        add("backend", "gym.booking-horizon-days", "90", false, "How many days ahead a member may book");
        add("backend", "gym.timezone", "", false, "Time zone for deciding whether a class has started (empty = server default)");
        add("backend", "gym.seed-demo-data", "true", false, "Create demo plans, classes and members on an empty database");
        add("backend", "gym.admin.email", "admin@foundry.gym", false, "Email of the first admin account");
        add("backend", "gym.admin.password", "admin123", true, "Password of the first admin account (only used when it is created)");
        // Settings the browser UI reads (public: never put a secret here).
        add("ui", "brand.name", "Gym", false, "Name shown in the page header and browser tab");
        add("ui", "currency.symbol", "€", false, "Symbol shown in front of prices");
        add("ui", "banner.text", "", false, "Notice shown above every page (empty = none)");
        add("ui", "footer.text", "", false, "Replaces the footer line (empty = default)");
        add("ui", "api.base", "http://localhost:8080", false, "Address of the gym backend the UI talks to (open the UI and the API on the same host name)");
    }

    private void add(String app, String key, String value, boolean secret, String description) {
        ConfigEntry e = new ConfigEntry();
        e.setAppName(app);
        e.setProfileName(ConfigService.DEFAULT_PROFILE);
        e.setPropKey(key);
        e.setPropValue(value);
        e.setSecret(secret);
        e.setDescription(description);
        e.setUpdatedBy("seed");
        repo.save(e);
    }
}
