CREATE TABLE IF NOT EXISTS titles (
 id VARCHAR(160) PRIMARY KEY,
 kind ENUM('movie','series') NOT NULL,
 title VARCHAR(512) NOT NULL,
 year SMALLINT UNSIGNED,
 description TEXT,
 poster TEXT,
 backdrop TEXT,
 payload JSON,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 INDEX titles_kind_year (kind,year),
 INDEX titles_kind_updated_at (kind,updated_at),
 INDEX titles_title (title(150))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS episodes (
 title_id VARCHAR(160) NOT NULL,
 season_number SMALLINT UNSIGNED NOT NULL,
 episode_number SMALLINT UNSIGNED NOT NULL,
 title VARCHAR(512),
 air_date DATE NULL,
 still TEXT,
 payload JSON,
 PRIMARY KEY(title_id,season_number,episode_number),
 CONSTRAINT episode_title FOREIGN KEY (title_id) REFERENCES titles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS source_cache (
 cache_key CHAR(64) PRIMARY KEY,
 payload JSON NOT NULL,
 expires_at DATETIME NOT NULL,
 INDEX cache_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
