CREATE TABLE catalog_work (
    work_id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    summary VARCHAR(1000) NOT NULL DEFAULT '',
    description VARCHAR(10000) NOT NULL DEFAULT '',
    genre VARCHAR(120) NOT NULL,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 0,
    rating_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_work_title_valid CHECK (title = btrim(title) AND title <> ''),
    CONSTRAINT catalog_work_genre_valid CHECK (genre = btrim(genre) AND genre <> ''),
    CONSTRAINT catalog_work_rating_valid CHECK (rating BETWEEN 0 AND 5),
    CONSTRAINT catalog_work_rating_count_valid CHECK (rating_count >= 0),
    CONSTRAINT catalog_work_timestamp_order_valid CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_work_title_index ON catalog_work (title);
CREATE INDEX catalog_work_genre_index ON catalog_work (genre);

CREATE TABLE catalog_contributor (
    contributor_id UUID PRIMARY KEY,
    name VARCHAR(300) NOT NULL,
    biography VARCHAR(5000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_contributor_name_valid CHECK (name = btrim(name) AND name <> ''),
    CONSTRAINT catalog_contributor_timestamp_order_valid CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_contributor_name_index ON catalog_contributor (name);

CREATE TABLE catalog_work_contributor (
    work_id UUID NOT NULL REFERENCES catalog_work(work_id) ON DELETE RESTRICT,
    contributor_id UUID NOT NULL REFERENCES catalog_contributor(contributor_id) ON DELETE RESTRICT,
    contribution_role VARCHAR(32) NOT NULL,
    display_order INTEGER NOT NULL,
    PRIMARY KEY (work_id, contributor_id, contribution_role),
    CONSTRAINT catalog_work_contributor_role_valid
        CHECK (contribution_role IN ('AUTHOR', 'EDITOR', 'TRANSLATOR')),
    CONSTRAINT catalog_work_contributor_order_valid CHECK (display_order BETWEEN 0 AND 1000)
);

CREATE INDEX catalog_work_contributor_lookup_index
    ON catalog_work_contributor (contributor_id, work_id);

CREATE TABLE catalog_edition (
    edition_id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES catalog_work(work_id) ON DELETE RESTRICT,
    title VARCHAR(500) NOT NULL,
    isbn VARCHAR(32) NOT NULL UNIQUE,
    publisher VARCHAR(300) NOT NULL,
    publication_year INTEGER NOT NULL,
    language VARCHAR(80) NOT NULL,
    page_count INTEGER NOT NULL,
    cover_url VARCHAR(2048),
    cover_color CHAR(7),
    video_url VARCHAR(2048),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_edition_title_valid CHECK (title = btrim(title) AND title <> ''),
    CONSTRAINT catalog_edition_isbn_valid CHECK (isbn = btrim(isbn) AND isbn <> ''),
    CONSTRAINT catalog_edition_publisher_valid
        CHECK (publisher = btrim(publisher) AND publisher <> ''),
    CONSTRAINT catalog_edition_year_valid CHECK (publication_year BETWEEN 1000 AND 3000),
    CONSTRAINT catalog_edition_language_valid CHECK (language = btrim(language) AND language <> ''),
    CONSTRAINT catalog_edition_page_count_valid CHECK (page_count > 0),
    CONSTRAINT catalog_edition_cover_color_valid
        CHECK (cover_color IS NULL OR cover_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT catalog_edition_timestamp_order_valid CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_edition_work_index ON catalog_edition (work_id, is_active, edition_id);
CREATE INDEX catalog_edition_title_index ON catalog_edition (title);

-- Circulation remains the authority for physical copies. Catalog stores only
-- a disposable, versioned projection used by browse/search responses.
CREATE TABLE catalog_edition_availability_projection (
    edition_id UUID PRIMARY KEY REFERENCES catalog_edition(edition_id) ON DELETE RESTRICT,
    total_copies INTEGER NOT NULL,
    available_copies INTEGER NOT NULL,
    source_version BIGINT NOT NULL,
    source_occurred_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_availability_counts_valid
        CHECK (total_copies >= 0 AND available_copies BETWEEN 0 AND total_copies),
    CONSTRAINT catalog_availability_source_version_valid CHECK (source_version >= 0),
    CONSTRAINT catalog_availability_timestamp_order_valid
        CHECK (updated_at >= source_occurred_at)
);

CREATE TABLE catalog_review (
    review_id UUID PRIMARY KEY,
    work_id UUID NOT NULL REFERENCES catalog_work(work_id) ON DELETE RESTRICT,
    member_id UUID NOT NULL,
    rating SMALLINT NOT NULL,
    content VARCHAR(4000) NOT NULL,
    moderation_status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT catalog_review_member_work_unique UNIQUE (work_id, member_id),
    CONSTRAINT catalog_review_rating_valid CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT catalog_review_content_valid CHECK (content = btrim(content) AND content <> ''),
    CONSTRAINT catalog_review_status_valid
        CHECK (moderation_status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT catalog_review_timestamp_order_valid CHECK (updated_at >= created_at)
);

CREATE INDEX catalog_review_work_index
    ON catalog_review (work_id, moderation_status, created_at, review_id);
