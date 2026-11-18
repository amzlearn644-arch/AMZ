-- AMZ Cloudflare D1 schema.
-- Keep this schema aligned with app/db.py. The local SQLite database remains unchanged.

CREATE TABLE IF NOT EXISTS software (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    slug TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL DEFAULT 'ابزار کاربردی',
    os TEXT NOT NULL DEFAULT 'Windows',
    version TEXT NOT NULL DEFAULT '',
    icon_url TEXT NOT NULL DEFAULT '',
    homepage_url TEXT NOT NULL DEFAULT '',
    download_url TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'AMZ',
    source_id TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(source, source_id)
);

CREATE TABLE IF NOT EXISTS api_sources (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    url TEXT NOT NULL,
    token TEXT NOT NULL DEFAULT '',
    records_path TEXT NOT NULL DEFAULT 'items',
    mapping TEXT NOT NULL DEFAULT '{}',
    pagination TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sync_state (
    provider TEXT PRIMARY KEY,
    last_attempt TEXT NOT NULL DEFAULT '',
    last_success TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'idle',
    message TEXT NOT NULL DEFAULT ''
);

CREATE INDEX IF NOT EXISTS idx_software_os ON software(os);
CREATE INDEX IF NOT EXISTS idx_software_category ON software(category);
CREATE INDEX IF NOT EXISTS idx_software_name ON software(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_software_source ON software(source);
