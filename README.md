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

## Tests

    mvn test

## Before putting this on the internet

- Change all passwords and client secrets and serve over HTTPS (the secret travels in the token request and the token in every config request).
- Tokens are signed JWTs (HS256) with the client id, the applications it may read, and an expiry. The server keeps no list of issued tokens, so one cannot be revoked before it ends; keep the lifetime short.
- Client secrets must stay on a server. Never put one in code that runs in a browser: the UI project's server asks for the token and the browser never sees it.
- Turn on CSRF protection (it is off, as in the backend, because this is a POC).
- Token requests are rate limited per client id and address; reading `/config/**` with a token is not.
- Anything in the `ui` application ends up in the browser. Never put a secret there (secret entries in `ui` are never returned).
