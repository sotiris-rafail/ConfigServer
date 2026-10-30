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
        if (!props.seedDefaults()) {
            return;
        }
        boolean emptyDatabase = repo.count() == 0;
        // Settings added after the first release are put in when they are missing, also on an existing database.
        ensure("backend", "plans.unlimited-enabled", "true", false,
            "true = admins can create and assign unlimited plans, false = they cannot (members already on one keep it). Takes effect within 5 minutes, no restart");
        ensure("backend", "home.enabled", "true", false,
            "true = the home page items are shown to visitors and managed by the admin (Home page menu), false = visitors see no items and the admin Home page is hidden and refused. Takes effect within 5 minutes, no restart");
        ensure("backend", "gyms.multi-enabled", "false", false,
            "false = one gym (as before), true = the admin can set up several gyms (Gyms menu) and members can choose one. Switching it back to false hides the other gyms but deletes nothing. Takes effect within 5 minutes, no restart");
        ensure("backend", "analytics.enabled", "true", false,
            "true = the admin Analytics page is available (menu entry and its data), false = it is hidden and its data is refused. Takes effect within 5 minutes, no restart");
        ensure("backend", "analytics.follow-up-days", "30", false,
            "Analytics, 'Worth a message': members with a plan and no booking for more than this many days are listed (1-365). Takes effect within 5 minutes, no restart");
        ensure("backend", "analytics.low-fill-percent", "65", false,
            "Analytics, 'Most booked classes': a class filled less than this percent is highlighted as one of the emptiest (1-100). Takes effect within 5 minutes, no restart");
        // The backend's two secrets. Made once, at random, and never replaced: changing either one later would make every
        // stored email address unreadable and every password stop matching. Back them up with the config database.
        ensure("backend", "gym.security.password-salt", randomSecret(), true,
            "Secret salt mixed into every password before it is hashed. NEVER change it once accounts exist (all passwords would stop working)");
        ensure("backend", "gym.security.email-key", randomSecret(), true,
            "Key that encrypts every email address. NEVER change it once accounts exist (the addresses could no longer be read)");
        // Everything for the emails to members lives here, so the backend needs nothing but the config server's address.
        // Read when the backend starts (a change needs a restart). Each key is only added when missing.
        ensure("backend", "gym.mail.enabled", "false", false,
            "true = send the emails members asked for in Preferences and from the Marketing page; false = nothing is queued or sent, and members cannot turn emails on. Takes effect within 5 minutes, no restart. true with the mail server settings below incomplete is refused (the backend will not start)");
        ensure("backend", "gym.mail.from", "", false, "Sender address of the emails, e.g. Foundry Gym <no-reply@yourgym.com> (required when enabled). Takes effect within 5 minutes");
        ensure("backend", "gym.mail.app-url", "", false, "Address of the web app, shown in the emails so members find Preferences");
        ensure("backend", "gym.mail.gym-name", "Foundry", false, "Gym name used in the wording of the emails");
        ensure("backend", "gym.mail.currency", "€", false, "Currency symbol shown in front of prices in the new-plan email");
        ensure("backend", "gym.mail.scan-seconds", "900", false, "How often the jobs look for emails to queue (seconds). Read at start-up only");
        ensure("backend", "gym.mail.send-seconds", "60", false, "How often queued emails are sent (seconds). Read at start-up only");
        ensure("backend", "gym.mail.initial-delay-seconds", "30", false, "Wait after start-up before the first run (seconds). Read at start-up only");
        ensure("backend", "gym.mail.reminder-hour", "9", false, "The visits-left reminder goes out from this hour (gym time zone) on and after the 21st");
        ensure("backend", "gym.mail.news-window-days", "7", false, "A new plan is announced for this many days after it was created");
        ensure("backend", "gym.mail.max-attempts", "5", false, "A failing email is tried this many times before it is given up");
        ensure("backend", "gym.mail.batch-size", "50", false, "How many queued emails are sent per run");
        ensure("backend", "spring.mail.host", "", false, "SMTP server, e.g. smtp.gmail.com or email-smtp.eu-west-1.amazonaws.com (required when enabled). Takes effect within 5 minutes");
        ensure("backend", "spring.mail.port", "587", false, "SMTP port (587 with STARTTLS, 465 for SSL)");
        ensure("backend", "spring.mail.username", "", false, "SMTP user name. Takes effect within 5 minutes");
        ensure("backend", "spring.mail.password", "", true, "SMTP password (secret). Takes effect within 5 minutes");
        ensure("backend", "spring.mail.properties.mail.smtp.auth", "true", false, "Log in to the SMTP server");
        ensure("backend", "spring.mail.properties.mail.smtp.starttls.enable", "true", false, "Use STARTTLS (port 587)");
        if (!emptyDatabase) {
            return; // the other defaults are only for an empty database
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

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private void ensure(String app, String key, String value, boolean secret, String description) {
        if (repo.findByAppNameAndProfileNameAndPropKey(app, ConfigService.DEFAULT_PROFILE, key).isEmpty()) {
            add(app, key, value, secret, description);
        }
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
