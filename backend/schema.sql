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

CREATE TABLE IF NOT EXISTS accounts (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 email VARCHAR(254) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 disabled BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS account_sessions (
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX account_sessions_expiry (account_id,expires_at),
 FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS profiles (
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_index TINYINT UNSIGNED NOT NULL,
 name VARCHAR(48) NOT NULL,
 avatar_color CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '#1E88E5',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY (account_id,profile_index),
 FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
 CHECK (profile_index BETWEEN 1 AND 6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_items (
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_index TINYINT UNSIGNED NOT NULL,
 entity_type ENUM('progress','watched','library') NOT NULL,
 item_key VARCHAR(255) NOT NULL,
 payload JSON NULL,
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (account_id,profile_index,entity_type,item_key),
 FOREIGN KEY (account_id,profile_index) REFERENCES profiles(account_id,profile_index) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_mutations (
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 mutation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_id BIGINT UNSIGNED NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (account_id,mutation_id),
 INDEX sync_mutations_created (created_at),
 FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sync_events (
 event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 profile_index TINYINT UNSIGNED NOT NULL,
 mutation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 entity_type ENUM('progress','watched','library') NOT NULL,
 operation ENUM('upsert','delete') NOT NULL,
 item_key VARCHAR(255) NOT NULL,
 payload JSON NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (account_id,mutation_id),
 INDEX sync_events_cursor (account_id,profile_index,event_id),
 FOREIGN KEY (account_id,profile_index) REFERENCES profiles(account_id,profile_index) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
