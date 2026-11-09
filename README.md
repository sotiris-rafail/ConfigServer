# Foundry config server

One place to keep the settings of the Foundry gym projects. A Spring Boot app with a login page and a console for
editing settings. The gym backend reads its settings from it at start-up, and the UI server reads its (public)
settings when the page loads.

It is deliberately small and does not use Spring Cloud Config: the protocol is a plain JSON endpoint that is easy to
call from anything.

## Run it

Needs Java 17 and Maven.

    mvn spring-boot:run

Open http://localhost:8888 and log in. Start this **before** the backend. Data is kept in `./data/config` (H2 file
database). The schema is created by Liquibase (`src/main/resources/db/changelog`).

| Account | Default | Used for |
|---|---|---|
| Console admin | `admin` / `config123` | the login page, editing settings |
| Backend client | `backend` / `backend-secret` | the backend, to get a token (`secrets: true`, reads `backend`) |
| UI client | `ui` / `ui-secret` | the UI server, to get a token (`secrets: false`, reads `ui`) |

Change them with environment variables before using this for anything real:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | 8888 | port |
| `CONFIG_DB_PATH` | `./data/config` | database file |
| `CONFIG_ADMIN_USER` / `CONFIG_ADMIN_PASSWORD` | `admin` / `config123` | console login |
| `CONFIG_CLIENT_USER` / `CONFIG_CLIENT_PASSWORD` | `backend` / `backend-secret` | account the backend uses |
| `CONFIG_UI_CLIENT_ID` / `CONFIG_UI_CLIENT_SECRET` | `ui` / `ui-secret` | the UI server's client |
| `CONFIG_BACKEND_CLIENT_ID` / `CONFIG_BACKEND_CLIENT_SECRET` | `backend` / `backend-secret` | the backend's client |
| `CONFIG_TOKEN_SECRET` | random at every start | signs the tokens (set it so tokens survive a restart or work on several instances) |
| `CONFIG_TOKEN_TTL_SECONDS` | `900` | how long a token lives |
| `CONFIG_SEED_DEFAULTS` | `true` | on an empty database, create starter settings for the backend and UI |

## How settings are organised

Every setting has an **application** (`backend`, `ui`, or `application` for settings shared by all), a **profile**
(`default`, or anything such as `prod`), a **key**, a **value**, an optional **secret** flag and a note.

What an application receives is merged, lowest priority first:

1. `application` / `default`, then `application` / each requested profile
2. the app's own `default`, then its own requested profiles

A later layer overrides an earlier one for the same key, and profiles listed later beat profiles listed earlier. The
console's **Preview** shows the result and which layer each value came from.

Secrets are never shown in the console again after saving (leave the value empty when editing to keep it). They are
sent only to the backend's client account, never to browsers.

## Endpoints

| Endpoint | Who | What |
|---|---|---|
| `POST /auth/token` | none (client id and secret in the JSON body `{"clientId","clientSecret"}`) | `{"accessToken","tokenType":"Bearer","expiresIn","apps"}`. 5 wrong tries lock that client id and address for 5 minutes |
| `GET /config/{app}?profiles=a,b` | `Authorization: Bearer <token>` listing `app` | merged settings `{"app","profiles","properties":{key:value}}`. A client with `secrets: true` also gets the secret ones and the shared `application` settings; a client with `secrets: false` gets only that application's own non-secret ones |
| `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | console | session cookie login |
| `GET/POST /api/admin/entries`, `PUT/DELETE /api/admin/entries/{id}`, `GET /api/admin/apps`, `GET /api/admin/preview` | console admin | editing |

Five wrong passwords for one username and address pause that login for five minutes.

## Starter settings

On an empty database it creates:

- `backend`: `gym.cors-origins`, `gym.booking-horizon-days`, `gym.timezone`, `gym.seed-demo-data`, `gym.admin.email`,
  `gym.admin.password` (secret). Keys are the backend's own Spring property names, so any Spring property can be added.
- `ui`: `brand.name`, `currency.symbol`, `banner.text`, `footer.text`, `api.base` (the address of the backend).

It also makes sure, on an existing database too, that these exist (each only when missing, never replaced):
`backend` / `gym.security.password-salt` and `gym.security.email-key`, both secret, each a random 32-byte value made on the
first start. The backend uses them as the secret password salt and the email encryption key. **Never change or delete
them once accounts exist** (every password would stop matching and every address become unreadable), and back them up
together with the config database: if the database is lost the keys are lost with it.

It also makes sure that every setting of the emails to members exists for `backend` (each only when missing, never replaced):
`gym.mail.enabled` (`false`), `gym.mail.from`, `gym.mail.app-url`, `gym.mail.gym-name`, `gym.mail.currency`,
`gym.mail.scan-seconds`, `send-seconds`, `initial-delay-seconds`, `reminder-hour`, `news-window-days`, `max-attempts`,
`batch-size`, and the mail server: `spring.mail.host`, `port` (587), `username`, `password` (secret) and
`spring.mail.properties.mail.smtp.auth` / `starttls.enable`. To turn the emails on, fill in `spring.mail.host`, `username`,
`password` and `gym.mail.from` in the console and set `gym.mail.enabled` to `true`. **No restart is needed**: the backend asks
again every 5 minutes, so switching the emails on or off, or changing the mail server, user, password or sender, takes effect
within 5 minutes (only the three timer settings `scan-seconds`, `send-seconds` and `initial-delay-seconds` are read at start-up).
While `gym.mail.enabled` is `false` nothing is queued or sent and members cannot turn emails on in their Preferences. A change
that would leave the emails on but incomplete (no host or sender) is refused by the backend, which keeps the previous settings
and logs why (see the backend README).

It also makes sure `backend` / `plans.unlimited-enabled` (`true`) `home.enabled` (`true`), `gyms.multi-enabled` (`false`) and the three Analytics settings (`analytics.enabled` `true`, `analytics.follow-up-days` `30`, `analytics.low-fill-percent` `65`) exist, on an existing database too if it is missing.
Set it to `false` in the console to stop admins from creating or assigning unlimited plans; the backend notices within
at most 5 minutes and no restart is needed (see the backend README).

## Tests

    mvn test

## Before putting this on the internet

- Change all passwords and client secrets and serve over HTTPS (the secret travels in the token request and the token in every config request).
- Tokens are signed JWTs (HS256) with the client id, the applications it may read, and an expiry. The server keeps no list of issued tokens, so one cannot be revoked before it ends; keep the lifetime short.
- Client secrets must stay on a server. Never put one in code that runs in a browser: the UI project's server asks for the token and the browser never sees it.
- Turn on CSRF protection (it is off, as in the backend, because this is a POC).
- Token requests are rate limited per client id and address; reading `/config/**` with a token is not.
- Anything in the `ui` application ends up in the browser. Never put a secret there (secret entries in `ui` are never returned).


### Analytics settings (live)

`cancellation.cutoff-hours` (0-168, default `0` = members can cancel until the class starts), `waitlist.enabled` (`true` lets members join a waiting list for a full class; default `false`), `marketing.enabled` (`false` hides the admin Marketing page and refuses its data), `analytics.enabled` (`false` hides the admin Analytics page and refuses its data), `analytics.follow-up-days` (1-365,
"Worth a message" lists members with a plan and no booking for longer than this) and `analytics.low-fill-percent`
(1-100, classes filled less than this are highlighted). The backend reads them again every 5 minutes and the admin UI
asks the backend once a minute, so a change shows up without restarting anything. An environment variable with the same
name (for example `ANALYTICS_ENABLED`) fixes that one value and beats the config server.


### Several gyms switch (live)

`gyms.multi-enabled` (default `false` = one gym, exactly as before). Set to `true` and the admin gets a Gyms menu entry
(add gyms, choose the default gym, stop new bookings, archive) and members can choose which gym they train at. Set it back
to `false` and the other gyms are hidden, not deleted: bookings already made stay valid, members keep their plans (valid
at the default gym) and switching on again brings everything back. Before switching off, the backend's Gyms page offers a
"switch-off check". The backend reads it again every 5 minutes and every open page asks once a minute. An environment
variable `GYMS_MULTI_ENABLED` fixes the value and beats the config server.

### Home page switch (live)

`home.enabled` (default `true`). Set to `false` and visitors and members see no home page items, while the admin's
Home page menu entry is hidden and its data refused (404). Nothing is deleted: switching it back on brings every item
back. The backend reads it again every 5 minutes and every open page asks the backend once a minute, so no restart
or reload is needed. An environment variable `HOME_ENABLED` fixes the value and beats the config server.

### Switches added later (backend app, live unless noted)
`noshow.block-after` (0-50, default `0`) and `noshow.window-days` (1-365, default `60`): members with that many no-shows in the window cannot book. `security.persistent-login-throttle` (`true` keeps wrong-password counting in the database). `reports.enabled` (CSV downloads), `bulk.enabled` (change many members' plans at once), `staff.enabled` (coach accounts that can check people in), `payments.enabled` (members subscribe online with Stripe; needs the secrets `payments.stripe-secret-key` and `payments.stripe-webhook-secret`, which are read when the backend starts), `waitlist.offer-minutes` (0-1440, default `0`: minutes a waiting-list place is held for the member to accept; 0 = booked at once), `alerts.enabled` (email admins about backup/email/payment problems), `retention.days` (delete old audit/email records after N days, 0 = keep), `addtocal.enabled` (members: add a class to their calendar), `latecancel.enabled` (late cancels allowed but counted as strikes), `referral.enabled` + `referral.reward-visits` (bring a friend, reward in visits; needs packs), `duplicate.enabled` (admin: duplicate a class), `recurring.enabled` (members book a class for several weeks at once), `qrcheckin.enabled` (QR code check-in), `bulktools.enabled` (admin: edit several classes at once, import members from CSV), `selfservice.enabled` (member history, receipts, pause/cancel), `packs.enabled` (class packs), `status.enabled` (admin System status card), `push.enabled` (browser notifications for members who allow them; needs HTTPS), `offline.enabled` (members: saved read-only copy of the schedule and bookings when offline), `calendarview.enabled` (members: week grid next to the month calendar), `waitlist.position.enabled` (members: place in the queue, e.g. 2 of 5, and time left on an offer), `announcements.enabled` (admin notices that members see in the app). All default to off/0. See the backend README for details.

## Console features (added)

- **Checked values**: known settings are validated (true/false, number ranges, time zone, e-mail). A bad value is refused with a message.
- **Groups, search, hints**: the Settings tab groups settings (Booking rules, Email, ...) and has a search box. A banner warns about settings that are on but cannot work (for example referral without packs, alerts without mail).
- **History and undo**: every create/change/delete is recorded (who, when, old and new value). Undo restores the old value. Secret values are never recorded and cannot be undone.
- **Secrets encrypted at rest**: secret values are stored AES-256-GCM encrypted (`enc1:` prefix) and existing plain secrets are encrypted at startup. The key is `CONFIG_ENCRYPTION_KEY`, or a key file at `CONFIG_ENCRYPTION_KEY_FILE` (default `./data/config.key`, created on first start). **Back the key up and keep it separate from the database; without it the secrets cannot be read.**
- **Two-step sign-in**: Security tab > Set up. Add the key to an authenticator app and confirm with a code. Login then asks for the 6-digit code.
- **Clients tab**: shows when each client last read its settings, how many changes it has not read yet, and lets you switch a client off (its token and reads are refused) and on again.
- **Refresh now**: set `refresh-url` on a client (`CONFIG_BACKEND_REFRESH_URL`, for example `http://backend:8080/api/internal/config-refresh`). The button then calls it with the client secret and the backend re-reads its settings immediately. The backend answers only to its own client secret.
- **Tools tab**: export (secrets only if ticked), import with a "Check only" run, and copy one profile into another.
- The `delete` and `update` calls now record the signed-in user in the history.
