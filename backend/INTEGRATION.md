# Branch integration (2026-10-28)

The current main includes selectively ported functionality from:
- `feature/server-catalog-sync`: `sync-catalog.mjs`, admin UI, admin API and scheduler. The scheduler runs only with ADMIN_SESSION_SECRET and ADMIN_PASSWORD_SCRYPT.
- `feat/mariadb-accounts-history`: accounts, profiles, history/sync API and migrations 002/003. The existing catalogue API remains intact.

Do not deploy by merging the diverged feature branch wholesale. Do not enable accounts until AUTH_JWT_SECRET is at least 32 bytes and the MariaDB migrations have completed. Use a unique CINEMA_PUBLISHABLE_KEY as configured in the account API documentation.

## Deployment sequence on existing small VPS

1. Backup the `cinemahall` database, not QuickShift:
   `mariadb-dump cinemahall > /root/cinemahall-before-migrate.sql` (protect the backup).
2. Pull main into the existing `/opt/cinemahall-source` checkout. Keep Nginx unchanged.
3. Run `node --check backend/server.mjs`, `node --check backend/accounts.mjs`, `node --check backend/admin.mjs`, and `node --check backend/sync-catalog.mjs`.
4. Apply the isolated migrations only to `cinemahall`: `mariadb cinemahall < backend/migrations/002_accounts_profiles_sync.sql`, then `003_sync_items_snapshot_index.sql`, then `004_admin_settings.sql`. Check for existing indexes first before reapplying `003`.
5. Set account/admin secrets in root-owned EnvironmentFile and restart `cinemahall-api` only after verifying all required tables and secrets.
6. Verify local `http://127.0.0.1:8091/health` and the existing QuickShift HTTP 200. Keep the Android client on its existing stable API until end-to-end tests pass.

Catalogue sync requires KINOAPI_KEY, network/API permission and a bounded resource budget. On the 1 vCPU / 1GB machine, avoid simultaneous bootstrap and daily jobs. No torrent payloads, videos or credentials should be stored in the metadata database.

This integration was committed remotely without a verified production build or live server execution; validate before deploy.
