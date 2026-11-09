package com.foundry.config.service;

import com.foundry.config.ConfigProps;
import com.foundry.config.domain.ConfigEntry;
import com.foundry.config.repo.ConfigEntryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Fills an empty database with starter settings so the backend and UI work from the first start. */
@Component
@org.springframework.core.annotation.Order(1)
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
        ensure("backend", "marketing.enabled", "true", false,
            "true = the admin Marketing page is available (menu entry and its data), false = it is hidden and refused (emails already queued are still sent). Takes effect within 5 minutes, no restart");
        ensure("backend", "waitlist.enabled", "false", false,
            "true = members can join a waiting list for a full class and are booked automatically when a seat opens, false = no waiting list (people already on one are not booked). Takes effect within 5 minutes, no restart");
        ensure("backend", "cancellation.cutoff-hours", "0", false,
            "Members cannot cancel a booking less than this many hours before the class starts (0-168). 0 = no limit. The admin can still help them. Takes effect within 5 minutes, no restart");
        ensure("backend", "noshow.block-after", "0", false,
            "Members with this many no-shows (marked by the admin) inside the window cannot book until it clears (0-50). 0 = no rule. Takes effect within 5 minutes, no restart");
        ensure("backend", "noshow.window-days", "60", false,
            "How many days back no-shows count for noshow.block-after (1-365). Takes effect within 5 minutes, no restart");
        ensure("backend", "security.persistent-login-throttle", "false", false,
            "true = wrong-password counting is kept in the database (survives restarts, shared by several servers), false = in memory. Takes effect within 5 minutes, no restart");
        ensure("backend", "reports.enabled", "false", false,
            "true = the admin can download CSV exports (members, bookings, attendance, income), false = hidden and refused. Takes effect within 5 minutes, no restart");
        ensure("backend", "bulk.enabled", "false", false,
            "true = the admin can change the plan of many members at once on the Members page, false = hidden and refused. Takes effect within 5 minutes, no restart");
        ensure("backend", "staff.enabled", "false", false,
            "true = the admin can create coach accounts that sign in on the Staff tab and check people in, but cannot change plans, prices or members. false = coach accounts cannot sign in. Takes effect within 5 minutes, no restart");
        ensure("backend", "payments.enabled", "false", false,
            "true = members can buy a plan online with Stripe (needs the two payments.stripe-* secrets, set before the backend starts), false = plans are only assigned by the admin. Takes effect within 5 minutes, no restart");
        ensure("backend", "payments.stripe-secret-key", "", true,
            "Stripe secret API key (sk_live_... or sk_test_...). Only read by the backend, never shown to browsers. Read when the backend starts: restart it after changing");
        ensure("backend", "payments.stripe-webhook-secret", "", true,
            "Stripe webhook signing secret (whsec_...) for the endpoint https://<your-domain>/api/payments/webhook (event types checkout.session.completed and customer.subscription.deleted). Read when the backend starts");
        ensure("backend", "push.enabled", "false", false,
            "true = members can turn on browser notifications (class reminders, a place from the waiting list, a class cancelled or moved). Needs HTTPS. false = hidden, nothing is sent. Takes effect within 5 minutes, no restart");
        ensure("backend", "selfservice.enabled", "false", false,
            "true = members see their visit history, payment receipts and (when they pay through Stripe) can pause or cancel their own subscription on the Profile page. false = hidden, endpoints answer 404. Takes effect within 5 minutes, no restart");
        ensure("backend", "waitlist.offer-minutes", "0", false,
            "How many minutes a place offered from the waiting list is held for the member to accept it (0-1440). 0 = the member is booked at once. Each offer uses the value at the moment it is made, so changing it today does not affect offers already sent. Takes effect within 5 minutes, no restart");
        ensure("backend", "alerts.enabled", "false", false,
            "true = admins get an email (at most one a day per problem) when the newest backup fails its restore test or cannot be copied off the server, emails have been stuck for over an hour, over 10 emails failed, or a payment could not be matched to a member. Needs email switched on. Takes effect within 5 minutes, no restart");
        ensure("backend", "retention.days", "0", false,
            "Days to keep audit log entries, sent/failed emails, waiting-list offer logs and late-cancel records before they are deleted (checked once a day). 0 = keep everything. Otherwise at least 30. Takes effect within 5 minutes, no restart");
        ensure("backend", "offline.enabled", "false", false,
            "true = members can open the class schedule and their bookings with no connection (a saved read-only copy, with a notice; booking and cancelling still need a connection). The copy is removed at log out. false = off. Takes effect within 5 minutes, no restart");
        ensure("backend", "calendarview.enabled", "false", false,
            "true = members can switch the schedule between the list and a week/month calendar grid with their own bookings marked. false = list only. Takes effect within 5 minutes, no restart");
        ensure("backend", "waitlist.position.enabled", "false", false,
            "true = a member on a waiting list sees their place in the queue (for example 2nd of 5). Needs waitlist.enabled too. false = hidden. Takes effect within 5 minutes, no restart");
        ensure("backend", "announcements.enabled", "false", false,
            "true = admins can post notices (to all members, or to the members of one class) that members see in the app, and optionally send by push. false = hidden, endpoints answer 404. Takes effect within 5 minutes, no restart");
        ensure("backend", "addtocal.enabled", "false", false,
            "true = members get an Add to calendar button on each booked class (downloads a calendar file for that class). false = hidden, endpoint answers 404. Takes effect within 5 minutes, no restart");
        ensure("backend", "latecancel.enabled", "false", false,
            "true = a member may cancel inside the cancellation cutoff (cancellation.cutoff-hours), but it is counted as a late cancellation next to no-shows, so it can pause their booking under noshow.block-after / noshow.window-days. false = late cancels are refused, as before. Takes effect within 5 minutes, no restart");
        ensure("backend", "referral.enabled", "false", false,
            "true = bring a friend: each member gets a code, a new member who signs up with it links to them, and when that friend's first class is attended both get referral.reward-visits extra visits as a class pack. Needs packs.enabled too. false = hidden. Takes effect within 5 minutes, no restart");
        ensure("backend", "referral.reward-visits", "1", false,
            "Visits each side gets from a bring-a-friend reward (1-20). Read when the reward is given, so a change applies to the next one. Takes effect within 5 minutes, no restart");
        ensure("backend", "duplicate.enabled", "false", false,
            "true = admin sees a Duplicate button on each class (a copy with the same days, length and places, which can then be edited). false = hidden, endpoint answers 404. Takes effect within 5 minutes, no restart");
        ensure("backend", "recurring.enabled", "false", false,
            "true = members can book the same class on the same weekday for the next 2-12 weeks in one go (dates that are full, closed or out of visits are skipped and listed). false = hidden. Takes effect within 5 minutes, no restart");
        ensure("backend", "qrcheckin.enabled", "false", false,
            "true = members show a QR code for today's bookings and staff (admin, and coaches when staff.enabled is on) scan it, or type its code, to mark them as attended. false = hidden. Takes effect within 5 minutes, no restart");
        ensure("backend", "bulktools.enabled", "false", false,
            "true = admin bulk tools: change coach, places, level or start time of several classes at once, and import members from a CSV file (with a preview first). false = hidden, endpoints answer 404. Takes effect within 5 minutes, no restart");
        ensure("backend", "packs.enabled", "false", false,
            "true = class packs: the admin can define packs of visits (and give one to a member), members can use a pack when their plan has no visits left or they have no plan, and buy packs online if Stripe is set up. false = hidden, existing packs are not used. Takes effect within 5 minutes, no restart");
        ensure("backend", "status.enabled", "false", false,
            "true = the admin Activity page shows a System status card (last backup and its restore test, failed emails, uptime). false = hidden and the endpoint answers 404. Takes effect within 5 minutes, no restart");
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
            "true = send the emails members asked for (Preferences, Marketing page); false = nothing is queued or sent and members cannot turn emails on. Applies within 5 minutes, no restart. true with incomplete mail server settings is refused (backend will not start, or keeps the previous settings)");
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
