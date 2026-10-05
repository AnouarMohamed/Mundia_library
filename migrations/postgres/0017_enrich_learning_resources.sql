ALTER TABLE "public"."learning_resources"
  ADD COLUMN "description" text,
  ADD COLUMN "cover_url" text,
  ADD COLUMN "cover_alt" varchar(300);

ALTER TABLE "public"."learning_resources"
  ADD CONSTRAINT "learning_resources_cover_metadata_valid"
  CHECK (
    ("cover_url" IS NULL AND "cover_alt" IS NULL)
    OR (
      "cover_url" ~ '^https://'
      AND char_length(btrim("cover_alt")) BETWEEN 3 AND 300
    )
  ),
  ADD CONSTRAINT "learning_resources_description_valid"
  CHECK (
    "description" IS NULL
    OR char_length(btrim("description")) BETWEEN 1 AND 4000
  );

DROP INDEX "public"."learning_resources_search_idx";

CREATE INDEX "learning_resources_search_idx"
  ON "public"."learning_resources" USING gin (
    to_tsvector(
      'simple',
      coalesce("title", '') || ' ' ||
      coalesce("author", '') || ' ' ||
      coalesce("category", '') || ' ' ||
      coalesce("description", '')
    )
  );
