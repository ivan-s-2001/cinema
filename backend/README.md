# CinemaHall API

Small isolated Node.js 24 / MariaDB service for a 1-vCPU/1-GB VPS. It binds **only** to 127.0.0.1:8091. Use Nginx HTTPS as the public entry point.

## API

Public catalogue routes: GET `/health`, `/api/v1/home`, `/api/v1/search?q=...`, `/api/v1/titles/:id`, and `/api/v1/titles/:id/seasons`.

Account routes: POST `/api/v1/auth/register`, POST `/api/v1/auth/login`, GET `/api/v1/auth/me`, and POST `/api/v1/auth/logout`. Authentication returns a random bearer session token; only its SHA-256 hash is stored, and sessions expire after 30 days.

Profiles are account-owned and limited to six. GET/POST `/api/v1/profiles` lists or creates profiles; PUT/DELETE `/api/v1/profiles/:id` edits or removes one. Profile 1 is created at registration and cannot be deleted.

History and library data use one account-scoped change feed. POST `/api/v1/profiles/:id/sync` accepts up to 200 mutations (`progress`, `watched`, or `library`; each has a UUID `mutationId`, `itemKey`, `operation`, and JSON `payload`). GET on the same path accepts `cursor` and `limit` and returns ordered events. Repeated mutation IDs are idempotent. Deletes are retained as tombstones so other devices can receive them.

## Install and schema

Install with `npm install --omit=dev --no-audit --no-fund`; load DB_USER, DB_PASSWORD, DB_NAME from the protected environment file; start `node server.mjs`.

For a new database, apply `schema.sql`. For a database created from the original schema, apply `migrations/001_titles_kind_updated_at.sql` and `migrations/002_accounts_profiles_sync.sql` once, before deploying the new API. The CLI prompts for the password:

```sh
mariadb --user="$DB_USER" --password --database="$DB_NAME" < migrations/001_titles_kind_updated_at.sql
mariadb --user="$DB_USER" --password --database="$DB_NAME" < migrations/002_accounts_profiles_sync.sql
```

Passwords use Node's scrypt implementation. Registration and login are rate-limited per source address. The current API does not send verification or password-reset email; configure mail delivery and recovery before treating accounts as production-ready. Keep the service behind HTTPS and do not expose port 8091 publicly.

## Current limits

The app still stores data locally and its existing cloud account/sync code uses Supabase. The Android TV client has not yet been switched to these MariaDB routes; do not enable account sync in a release until that adapter is implemented and checked. Account tables start empty. Remote catalogue ingest and source search are also not implemented. This API stores no torrent files or playback URLs.

Home and search keep up to 64 successful responses in process memory for 10 seconds. MariaDB uses `(kind, updated_at)` for the newest-title shelves. Substring search (`LIKE '%query%'`) remains a scan to preserve current behavior.

The existing QuickShift Nginx setup must not be modified. Nginx should proxy only the dedicated CinemaHall hostname to this local listener.
