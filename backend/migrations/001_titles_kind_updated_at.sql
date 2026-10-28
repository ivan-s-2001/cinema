-- Run once on an existing CinemaHall database before deploying the API cache.
ALTER TABLE titles ADD INDEX titles_kind_updated_at (kind, updated_at);
