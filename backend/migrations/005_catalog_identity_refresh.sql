-- Additive; apply before the new sync-catalog version. Existing title IDs are retained.
CREATE TABLE IF NOT EXISTS title_external_ids (
 provider VARCHAR(32) NOT NULL,
 kind ENUM('movie','series') NOT NULL,
 external_id VARCHAR(48) NOT NULL,
 title_id VARCHAR(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
 PRIMARY KEY(provider,kind,external_id),
 INDEX identity_title (title_id),
 CONSTRAINT external_id_title FOREIGN KEY(title_id) REFERENCES titles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS title_refresh_state (
 title_id VARCHAR(160) PRIMARY KEY,
 last_success_at DATETIME(3) NULL,
 next_refresh_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX refresh_due(next_refresh_at,title_id),
 CONSTRAINT refresh_title FOREIGN KEY(title_id) REFERENCES titles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
