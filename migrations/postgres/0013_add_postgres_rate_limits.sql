-- Durable, cross-instance security budgets for deployments that cannot use
-- Redis. Only SHA-256 identifier digests are stored; raw IPs and account names
-- must never be written to this table.

CREATE TABLE "public"."rate_limit_buckets" (
  "scope" varchar(64) NOT NULL,
  "identifier_hash" varchar(64) NOT NULL,
  "request_count" integer DEFAULT 1 NOT NULL,
  "window_started_at" timestamp with time zone DEFAULT now() NOT NULL,
  "expires_at" timestamp with time zone NOT NULL,
  CONSTRAINT "rate_limit_buckets_pkey"
    PRIMARY KEY ("scope", "identifier_hash"),
  CONSTRAINT "rate_limit_buckets_identifier_hash_valid"
    CHECK (char_length("identifier_hash") = 64),
  CONSTRAINT "rate_limit_buckets_request_count_positive"
    CHECK ("request_count" > 0),
  CONSTRAINT "rate_limit_buckets_window_valid"
    CHECK ("expires_at" > "window_started_at")
);

CREATE INDEX "rate_limit_buckets_expiry_idx"
  ON "public"."rate_limit_buckets" ("expires_at");
