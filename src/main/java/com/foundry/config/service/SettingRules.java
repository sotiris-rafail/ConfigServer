package com.foundry.config.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.foundry.config.web.ApiException;

/**
 * What each known setting may hold, which group it is shown under in the console, and which settings only work together.
 * Unknown keys are not restricted. Checked when a setting is saved, so a typo like {@code tru} or {@code -5} is refused with a clear
 * message instead of being quietly ignored by the app.
 */
public final class SettingRules {

    private SettingRules() {
    }

    public enum Kind { BOOL, INT, TEXT }

    public record Rule(Kind kind, int min, int max, String hint) {
    }

    private static final Map<String, Rule> RULES = new java.util.HashMap<>();

    private static void bool(String... keys) {
        for (String k : keys) {
            RULES.put(k, new Rule(Kind.BOOL, 0, 1, "true or false"));
        }
    }

    private static void num(String key, int min, int max) {
        RULES.put(key, new Rule(Kind.INT, min, max, "whole number " + min + " to " + max));
    }

    static {
        bool("plans.unlimited-enabled", "home.enabled", "gyms.multi-enabled", "analytics.enabled", "marketing.enabled", "waitlist.enabled",
            "security.persistent-login-throttle", "reports.enabled", "bulk.enabled", "staff.enabled", "payments.enabled", "push.enabled",
            "selfservice.enabled", "alerts.enabled", "addtocal.enabled", "latecancel.enabled", "referral.enabled", "duplicate.enabled", "offline.enabled", "calendarview.enabled", "waitlist.position.enabled", "announcements.enabled",
            "recurring.enabled", "qrcheckin.enabled", "bulktools.enabled", "packs.enabled", "status.enabled", "gym.mail.enabled", "gym.seed-demo-data",
            "spring.mail.properties.mail.smtp.auth", "spring.mail.properties.mail.smtp.starttls.enable");
        num("cancellation.cutoff-hours", 0, 168);
        num("noshow.block-after", 0, 50);
        num("noshow.window-days", 1, 365);
        num("waitlist.offer-minutes", 0, 1440);
        num("retention.days", 0, 3650);
        num("referral.reward-visits", 1, 20);
        num("analytics.follow-up-days", 1, 365);
        num("analytics.low-fill-percent", 0, 100);
        num("gym.mail.scan-seconds", 1, 86400);
        num("gym.mail.send-seconds", 1, 86400);
        num("gym.mail.initial-delay-seconds", 0, 86400);
        num("gym.mail.reminder-hour", 0, 23);
        num("gym.mail.news-window-days", 1, 365);
        num("gym.mail.max-attempts", 1, 20);
        num("gym.mail.batch-size", 1, 1000);
        num("spring.mail.port", 1, 65535);
        num("gym.booking-horizon-days", 1, 365);
    }

    public static Rule rule(String key) {
        return RULES.get(key);
    }

    /** Throws a 400 with a plain message when the value is not allowed for this key. Secrets are not checked (they are not shown). */
    public static void validate(String key, String value) {
        Rule r = RULES.get(key);
        String v = value == null ? "" : value.trim();
        if (r != null) {
            switch (r.kind()) {
                case BOOL -> {
                    if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                        throw ApiException.badRequest(key + " must be true or false.");
                    }
                }
                case INT -> {
                    int n;
                    try {
                        n = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw ApiException.badRequest(key + " must be a whole number from " + r.min() + " to " + r.max() + ".");
                    }
                    if (n < r.min() || n > r.max()) {
                        throw ApiException.badRequest(key + " must be a whole number from " + r.min() + " to " + r.max() + ".");
                    }
                    if (key.equals("retention.days") && n != 0 && n < 30) {
                        throw ApiException.badRequest("retention.days must be 0 (keep everything) or at least 30.");
                    }
                }
                default -> { }
            }
        }
        if (key.equals("gym.timezone") && !v.isEmpty()) {
            try {
                ZoneId.of(v);
            } catch (DateTimeException e) {
                throw ApiException.badRequest("gym.timezone must be a time zone name such as Europe/Athens.");
            }
        }
    }

    /** Short text shown under the key in the console ("true or false", "whole number 0 to 1440"). */
    public static String hint(String key) {
        Rule r = RULES.get(key);
        return r == null ? null : r.hint();
    }

    /** The console heading a setting is listed under. */
    public static String group(String app, String key) {
        if (app.equals("ui")) {
            return "Browser UI";
        }
        if (key.startsWith("gym.mail.") || key.startsWith("spring.mail.")) {
            return "Email";
        }
        if (key.startsWith("security.") || key.startsWith("gym.security.") || key.startsWith("gym.admin.")) {
            return "Security and first admin";
        }
        if (key.startsWith("payments.") || key.equals("plans.unlimited-enabled") || key.equals("packs.enabled")) {
            return "Plans and payments";
        }
        if (key.startsWith("waitlist.") || key.startsWith("cancellation.") || key.startsWith("noshow.") || key.equals("recurring.enabled")
            || key.equals("qrcheckin.enabled") || key.equals("addtocal.enabled") || key.equals("latecancel.enabled") || key.equals("gym.booking-horizon-days")) {
            return "Booking rules";
        }
        if (key.equals("offline.enabled") || key.equals("calendarview.enabled") || key.equals("waitlist.position.enabled") || key.equals("announcements.enabled")
            || key.startsWith("referral.") || key.equals("selfservice.enabled") || key.equals("push.enabled")) {
            return "Member features";
        }
        if (key.endsWith(".enabled") || key.startsWith("analytics.") || key.startsWith("retention.")) {
            return "Admin tools";
        }
        return "Other";
    }

    private static boolean on(Map<String, String> m, String key) {
        return "true".equalsIgnoreCase(m.getOrDefault(key, "").trim());
    }

    private static int num(Map<String, String> m, String key) {
        try {
            return Integer.parseInt(m.getOrDefault(key, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean has(Map<String, String> m, String key) {
        return !m.getOrDefault(key, "").isBlank();
    }

    /** Things that are switched on but cannot work because another setting they need is off or empty. {@code merged} is what the app receives. */
    public static List<String> warnings(Map<String, String> m) {
        List<String> out = new ArrayList<>();
        if (on(m, "referral.enabled") && !on(m, "packs.enabled")) {
            out.add("referral.enabled is on but packs.enabled is off: the reward is a class pack, so referrals stay hidden until class packs are on.");
        }
        if (on(m, "alerts.enabled") && !on(m, "gym.mail.enabled")) {
            out.add("alerts.enabled is on but gym.mail.enabled is off: alert emails cannot be sent.");
        }
        if (on(m, "gym.mail.enabled") && !has(m, "spring.mail.host")) {
            out.add("gym.mail.enabled is on but spring.mail.host is empty: no email can be sent.");
        }
        if (on(m, "payments.enabled") && (!has(m, "payments.stripe-secret-key") || !has(m, "payments.stripe-webhook-secret"))) {
            out.add("payments.enabled is on but a Stripe key or webhook secret is empty.");
        }
        if (num(m, "waitlist.offer-minutes") > 0 && !on(m, "waitlist.enabled")) {
            out.add("waitlist.offer-minutes is above 0 but waitlist.enabled is off: there is no waiting list to make offers from.");
        }
        if (on(m, "waitlist.position.enabled") && !on(m, "waitlist.enabled")) {
            out.add("waitlist.position.enabled is on but waitlist.enabled is off: there is no waiting list to show a place in.");
        }
        if (on(m, "latecancel.enabled") && num(m, "cancellation.cutoff-hours") == 0) {
            out.add("latecancel.enabled is on but cancellation.cutoff-hours is 0: there is no cutoff, so nothing counts as late.");
        }
        if (on(m, "latecancel.enabled") && num(m, "noshow.block-after") == 0) {
            out.add("latecancel.enabled is on but noshow.block-after is 0: late cancellations are counted but never pause anyone's booking.");
        }
        if (on(m, "qrcheckin.enabled") && !on(m, "staff.enabled")) {
            out.add("qrcheckin.enabled is on but staff.enabled is off: only admins can scan codes, coaches cannot sign in.");
        }
        if (on(m, "push.enabled") && !has(m, "gym.mail.app-url")) {
            out.add("push.enabled is on but gym.mail.app-url is empty: notification links will not open the app.");
        }
        return out;
    }

    /** Lower-case compare helper used by callers that accept "TRUE"/"True". */
    public static String norm(String v) {
        return v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
    }
}
