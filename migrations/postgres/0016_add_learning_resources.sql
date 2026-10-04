CREATE TABLE "public"."learning_resources" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "source_name" varchar(64) NOT NULL,
  "source_record_key" varchar(512) NOT NULL,
  "title" varchar(500) NOT NULL,
  "author" varchar(500),
  "category" varchar(128) NOT NULL,
  "language" varchar(16) NOT NULL,
  "license_expression" varchar(64),
  "license_url" text,
  "source_url" text NOT NULL,
  "download_url" text,
  "read_url" text,
  "verification_status" varchar(16) DEFAULT 'QUARANTINED' NOT NULL,
  "verification_reason" varchar(500) NOT NULL,
  "verification_evidence_url" text,
  "content_hash" varchar(64) NOT NULL,
  "source_revision" varchar(128) NOT NULL,
  "verified_at" timestamp with time zone,
  "verified_by" uuid,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "learning_resources_verified_by_users_id_fk"
    FOREIGN KEY ("verified_by") REFERENCES "public"."users"("id") ON DELETE RESTRICT,
  CONSTRAINT "learning_resources_source_record_unique"
    UNIQUE ("source_name", "source_record_key"),
  CONSTRAINT "learning_resources_source_name_valid"
    CHECK (char_length(btrim("source_name")) BETWEEN 2 AND 64),
  CONSTRAINT "learning_resources_source_record_key_valid"
    CHECK (char_length(btrim("source_record_key")) BETWEEN 1 AND 512),
  CONSTRAINT "learning_resources_status_valid"
    CHECK ("verification_status" IN ('QUARANTINED', 'VERIFIED', 'REJECTED')),
  CONSTRAINT "learning_resources_content_hash_valid"
    CHECK ("content_hash" ~ '^[0-9a-f]{64}$'),
  CONSTRAINT "learning_resources_source_url_https"
    CHECK ("source_url" ~ '^https://'),
  CONSTRAINT "learning_resources_optional_urls_https"
    CHECK (
      ("license_url" IS NULL OR "license_url" ~ '^https://')
      AND ("download_url" IS NULL OR "download_url" ~ '^https://')
      AND ("read_url" IS NULL OR "read_url" ~ '^https://')
      AND ("verification_evidence_url" IS NULL OR "verification_evidence_url" ~ '^https://')
    ),
  CONSTRAINT "learning_resources_verified_license_valid"
    CHECK (
      "verification_status" <> 'VERIFIED'
      OR "license_expression" IN ('CC-BY', 'CC-BY-SA', 'CC0', 'PUBLIC-DOMAIN')
    ),
  CONSTRAINT "learning_resources_verified_timestamp_valid"
    CHECK (
      (
        "verification_status" = 'VERIFIED'
        AND "verified_at" IS NOT NULL
        AND "verification_evidence_url" IS NOT NULL
      )
      OR "verification_status" <> 'VERIFIED'
    ),
  CONSTRAINT "learning_resources_timestamps_valid"
    CHECK ("updated_at" >= "created_at")
);

CREATE INDEX "learning_resources_public_category_idx"
  ON "public"."learning_resources"
  ("verification_status", "category", "title", "id");

CREATE INDEX "learning_resources_review_queue_idx"
  ON "public"."learning_resources"
  ("verification_status", "updated_at", "id");

CREATE INDEX "learning_resources_search_idx"
  ON "public"."learning_resources" USING gin (
    to_tsvector(
      'simple',
      coalesce("title", '') || ' ' || coalesce("author", '') || ' ' || coalesce("category", '')
    )
  );

CREATE TABLE "public"."learning_resource_reviews" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "resource_id" uuid NOT NULL,
  "reviewer_id" uuid NOT NULL,
  "decision" varchar(16) NOT NULL,
  "reason" varchar(1000) NOT NULL,
  "reviewed_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "learning_resource_reviews_resource_id_fk"
    FOREIGN KEY ("resource_id") REFERENCES "public"."learning_resources"("id") ON DELETE RESTRICT,
  CONSTRAINT "learning_resource_reviews_reviewer_id_fk"
    FOREIGN KEY ("reviewer_id") REFERENCES "public"."users"("id") ON DELETE RESTRICT,
  CONSTRAINT "learning_resource_reviews_decision_valid"
    CHECK ("decision" IN ('APPROVED', 'REJECTED')),
  CONSTRAINT "learning_resource_reviews_reason_valid"
    CHECK (char_length(btrim("reason")) BETWEEN 10 AND 1000)
);

CREATE INDEX "learning_resource_reviews_resource_idx"
  ON "public"."learning_resource_reviews" ("resource_id", "reviewed_at");

CREATE OR REPLACE FUNCTION "public"."protect_learning_resource_review"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'learning resource reviews are append-only';
END;
$$;

CREATE TRIGGER "learning_resource_reviews_protect_rows"
  BEFORE UPDATE OR DELETE ON "public"."learning_resource_reviews"
  FOR EACH ROW EXECUTE FUNCTION "public"."protect_learning_resource_review"();

CREATE TABLE "public"."learning_resource_import_runs" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "batch_key" varchar(64) NOT NULL UNIQUE,
  "source_name" varchar(64) NOT NULL,
  "source_revision" varchar(128) NOT NULL,
  "batch_offset" integer NOT NULL,
  "batch_limit" integer NOT NULL,
  "status" varchar(16) NOT NULL,
  "seen_count" integer DEFAULT 0 NOT NULL,
  "inserted_count" integer DEFAULT 0 NOT NULL,
  "updated_count" integer DEFAULT 0 NOT NULL,
  "unchanged_count" integer DEFAULT 0 NOT NULL,
  "quarantined_count" integer DEFAULT 0 NOT NULL,
  "verified_count" integer DEFAULT 0 NOT NULL,
  "error_code" varchar(64),
  "started_at" timestamp with time zone DEFAULT now() NOT NULL,
  "finished_at" timestamp with time zone,
  CONSTRAINT "learning_resource_import_runs_batch_valid"
    CHECK ("batch_offset" >= 0 AND "batch_limit" BETWEEN 1 AND 250),
  CONSTRAINT "learning_resource_import_runs_status_valid"
    CHECK ("status" IN ('RUNNING', 'COMPLETED', 'FAILED')),
  CONSTRAINT "learning_resource_import_runs_counts_valid"
    CHECK (
      "seen_count" >= 0 AND "inserted_count" >= 0 AND
      "updated_count" >= 0 AND "unchanged_count" >= 0 AND
      "quarantined_count" >= 0 AND "verified_count" >= 0
    )
);

CREATE INDEX "learning_resource_import_runs_source_idx"
  ON "public"."learning_resource_import_runs" ("source_name", "started_at");
