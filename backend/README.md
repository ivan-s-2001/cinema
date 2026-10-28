# CinemaHall API (initial backend)

Small isolated Node.js 24 / MariaDB service for a 1-vCPU/1-GB VPS. It binds **only** to 127.0.0.1:8091. Use Nginx HTTPS as the public entry point.

Routes: GET /health, /api/v1/home, /api/v1/search?q=..., /api/v1/titles/:id, /api/v1/titles/:id/seasons.

Install: `npm install --omit=dev --no-audit --no-fund`; apply `schema.sql` to the CinemaHall database; load DB_USER, DB_PASSWORD, DB_NAME from the protected env file; start `node server.mjs`. For an existing database, apply `migrations/001_titles_kind_updated_at.sql` once before deploying this API version (the migration adds the same index as the fresh schema).

**Important:** Data tables start empty. This is a validated backend foundation, not a completed remote metadata ingest, source search or Android TV migration. Do not switch client traffic until ingestion and integration are implemented and tested. No torrent files or playback URLs are stored here.

The home and search routes keep up to 64 successful responses in process memory for 10 seconds. This cuts repeat reads while bounding memory; after catalogue ingestion is added, cached results may lag writes by at most 10 seconds. The home index `(kind, updated_at)` lets MariaDB select the newest 24 titles per type without sorting the full matching set. The existing `LIKE '%query%'` search remains a scan to preserve substring-search behavior; change it only with a measured, compatible search index strategy.

The existing QuickShift Nginx setup must not be modified. Nginx should proxy only the dedicated CinemaHall hostname to this local listener.
