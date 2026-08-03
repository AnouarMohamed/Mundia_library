-- Reconcile the canonical migration chain with the application schema.
--
-- This migration is deliberately additive. Unique indexes fail safely when
-- existing production data violates an invariant; operators must reconcile the
-- offending rows instead of silently deleting institutional records.

ALTER TYPE "public"."borrow_status" ADD VALUE IF NOT EXISTS 'PENDING' BEFORE 'BORROWED';

DO $$
BEGIN
  CREATE TYPE "public"."notification_type" AS ENUM (
    'INFO',
    'SUCCESS',
    'WARNING',
    'ERROR'
  );
EXCEPTION
  WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS "audit_logs" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "user_id" uuid NOT NULL REFERENCES "public"."users"("id"),
  "action" varchar(100) NOT NULL,
  "target_id" text,
  "target_type" varchar(50),
  "details" text,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "renewal_requests" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "borrow_record_id" uuid NOT NULL REFERENCES "public"."borrow_records"("id"),
  "user_id" uuid NOT NULL REFERENCES "public"."users"("id"),
  "status" "public"."status" DEFAULT 'PENDING' NOT NULL,
  "request_reason" text,
  "rejection_reason" text,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  "updated_at" timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS "notifications" (
  "id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
  "user_id" uuid NOT NULL REFERENCES "public"."users"("id"),
  "title" varchar(255) NOT NULL,
  "message" text NOT NULL,
  "type" "public"."notification_type" DEFAULT 'INFO' NOT NULL,
  "is_read" boolean DEFAULT false NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL
);

UPDATE "users" SET "status" = 'PENDING' WHERE "status" IS NULL;
UPDATE "users" SET "role" = 'USER' WHERE "role" IS NULL;
ALTER TABLE "users" ALTER COLUMN "status" SET NOT NULL;
ALTER TABLE "users" ALTER COLUMN "role" SET NOT NULL;

ALTER TABLE "borrow_records" ALTER COLUMN "due_date" DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS "book_reviews_one_per_user_book_idx"
  ON "book_reviews" ("user_id", "book_id");

CREATE UNIQUE INDEX IF NOT EXISTS "borrow_records_one_active_per_user_book_idx"
  ON "borrow_records" ("user_id", "book_id")
  WHERE "status" IN ('PENDING', 'BORROWED');

CREATE UNIQUE INDEX IF NOT EXISTS "renewal_requests_one_pending_per_loan_idx"
  ON "renewal_requests" ("borrow_record_id")
  WHERE "status" = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS "admin_requests_one_pending_per_user_idx"
  ON "admin_requests" ("user_id")
  WHERE "status" = 'PENDING';

CREATE INDEX IF NOT EXISTS "audit_logs_user_created_idx"
  ON "audit_logs" ("user_id", "created_at" DESC);
CREATE INDEX IF NOT EXISTS "audit_logs_target_created_idx"
  ON "audit_logs" ("target_type", "target_id", "created_at" DESC);
CREATE INDEX IF NOT EXISTS "renewal_requests_user_status_idx"
  ON "renewal_requests" ("user_id", "status");
CREATE INDEX IF NOT EXISTS "notifications_user_read_created_idx"
  ON "notifications" ("user_id", "is_read", "created_at" DESC);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'borrow_records_lifecycle_valid'
  ) THEN
    ALTER TABLE "borrow_records"
      ADD CONSTRAINT "borrow_records_lifecycle_valid"
      CHECK (
        (
          "status" = 'PENDING'
          AND "due_date" IS NULL
          AND "return_date" IS NULL
        )
        OR (
          "status" = 'BORROWED'
          AND "due_date" IS NOT NULL
          AND "return_date" IS NULL
        )
        OR (
          "status" = 'RETURNED'
          AND "return_date" IS NOT NULL
        )
      ) NOT VALID;
  END IF;
END $$;

-- Existing rows must be reconciled before this migration is allowed to
-- complete. PostgreSQL checks these constraints for new writes while they are
-- NOT VALID; validation below proves the historical dataset is clean as well.
ALTER TABLE "books"
  VALIDATE CONSTRAINT "books_total_copies_nonnegative";
ALTER TABLE "books"
  VALIDATE CONSTRAINT "books_available_copies_nonnegative";
ALTER TABLE "books"
  VALIDATE CONSTRAINT "books_available_copies_lte_total";
ALTER TABLE "books"
  VALIDATE CONSTRAINT "books_rating_range";
ALTER TABLE "borrow_records"
  VALIDATE CONSTRAINT "borrow_records_fine_amount_nonnegative";
ALTER TABLE "borrow_records"
  VALIDATE CONSTRAINT "borrow_records_renewal_count_nonnegative";
ALTER TABLE "borrow_records"
  VALIDATE CONSTRAINT "borrow_records_lifecycle_valid";
ALTER TABLE "book_reviews"
  VALIDATE CONSTRAINT "book_reviews_rating_range";
