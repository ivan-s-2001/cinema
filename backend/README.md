# CinemaHall API

Node.js 24 + MariaDB API for Cinema catalogue and Android TV account sync. The service listens on `127.0.0.1:8091`; publish it through HTTPS on a dedicated Cinema hostname.

## Android TV account sync

The Android TV client already speaks Supabase Auth and PostgREST RPC. This API implements the subset used for email/password accounts and cross-device sync, so the app can use the existing custom backend connection:

1. Open the Android TV app's custom server settings.
2. Set Backend URL to the HTTPS Cinema API root, with no trailing path (for example `https://api.example.com`).
3. Set Publishable key to the same value as `CINEMA_PUBLISHABLE_KEY` below.
4. Enable email/password auth. Keep TV/QR login disabled.
5. Save and create or sign into an account.

The API key is a public client identifier, not a database password. Use HTTPS. Do not ship `DB_PASSWORD` or `AUTH_JWT_SECRET` in the app.

Supported Supabase-compatible routes are `/auth/v1/signup`, `/auth/v1/token?grant_type=password|refresh_token`, `/auth/v1/user`, `/auth/v1/logout`, and `/rest/v1/rpc/:function`. Access JWTs last one hour; opaque refresh tokens are stored only as SHA-256 hashes and expire after 30 days. Passwords and profile PINs use scrypt hashes. Registration and login are rate-limited per source IP.

## Data model and sync

Accounts own up to six profiles. Each profile has playback progress, watched episodes, library items, add-ons, plugins, collections, and settings. Progress, watched, and library changes have a monotonic event cursor and delete tombstones. Repeated snapshots do not create duplicate events, so devices can reconcile offline changes without restoring removed items. Foreign keys cascade account deletion through sessions, profiles, sync state, and settings.

For clients that do not use the Supabase-compatible routes, direct API routes are also available: `/api/v1/auth/register|login|me|logout`, `/api/v1/profiles`, `/api/v1/profiles/:id`, and `/api/v1/profiles/:id/sync`.

## Install and schema

Install dependencies with `npm install --omit=dev --no-audit --no-fund`. Copy `.env.example` to a protected environment file, set the database credentials, a random `AUTH_JWT_SECRET` of at least 32 bytes, and a public `CINEMA_PUBLISHABLE_KEY`. Never commit the populated environment file. Start with `node server.mjs`.

For a new database, apply `schema.sql`. For an existing catalogue database, apply each migration once in order:

```sh
mariadb --user="$DB_USER" --password --database="$DB_NAME" < migrations/001_titles_kind_updated_at.sql
mariadb --user="$DB_USER" --password --database="$DB_NAME" < migrations/002_accounts_profiles_sync.sql
mariadb --user="$DB_USER" --password --database="$DB_NAME" < migrations/003_sync_items_snapshot_index.sql
```

The CLI prompts for the database password. Account tables start empty.

## Reverse proxy

Keep port 8091 private and forward the real client address so per-IP authentication limits work behind Nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:8091;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Use a separate Cinema hostname. The existing QuickShift Nginx setup must not be changed.

## Supported features and limits

Email/password sign-in, profiles, PINs, watch history, watched items, library, collections, and settings sync are supported by this backend. QR/device login, email verification, password reset, provider credential sync, paid memberships, and avatar catalog sync are not implemented. External provider credentials stay on the device. Add mail delivery and recovery before treating accounts as production-ready.

The public catalogue routes are GET `/health`, `/api/v1/home`, `/api/v1/search?q=...`, `/api/v1/titles/:id`, and `/api/v1/titles/:id/seasons`. Remote catalogue ingest and source search are not implemented; the API stores no torrent files or playback URLs.

Home and search cache up to 64 successful responses in process memory for 10 seconds. MariaDB indexes newest-title shelves and profile sync snapshots. Substring search (`LIKE '%query%'`) remains a scan to preserve current behavior.
