SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE guide_caches (
    url TEXT PRIMARY KEY,
    preview_status VARCHAR(20) NOT NULL,
    preview_started_at TIMESTAMP,
    thumbnail_url TEXT,
    favicon_url TEXT,
    is_video BOOLEAN NOT NULL DEFAULT FALSE,
    content_status VARCHAR(20) NOT NULL,
    content_started_at TIMESTAMP,
    content_text TEXT,
    title TEXT,
    site_name TEXT,
    author TEXT,
    published_at TIMESTAMP,
    canonical_url TEXT,
    final_url TEXT,
    language TEXT,
    is_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    collected_at TIMESTAMP,
    CONSTRAINT guide_caches_preview_status_check CHECK (preview_status IN ('NOT_STARTED', 'STARTED', 'READY', 'FAILED')),
    CONSTRAINT guide_caches_content_status_check CHECK (content_status IN ('NOT_STARTED', 'STARTED', 'READY', 'FAILED')),
    CONSTRAINT guide_caches_preview_started_check CHECK (preview_status <> 'STARTED' OR preview_started_at IS NOT NULL),
    CONSTRAINT guide_caches_content_started_check CHECK (content_status <> 'STARTED' OR content_started_at IS NOT NULL),
    CONSTRAINT guide_caches_ready_content_check CHECK (
        content_status <> 'READY' OR (content_text IS NOT NULL AND collected_at IS NOT NULL)
    )
);
