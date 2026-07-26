-- Store only the stable institutional identity binding. Provider access,
-- refresh, and ID tokens remain ephemeral and must never be persisted here.

CREATE TABLE "public"."federated_identities" (
  "binding_id" uuid DEFAULT gen_random_uuid() NOT NULL,
  "issuer" text NOT NULL,
  "subject" text NOT NULL,
  "user_id" uuid NOT NULL,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL,
  CONSTRAINT "federated_identities_issuer_subject_pk"
    PRIMARY KEY ("issuer", "subject"),
  CONSTRAINT "federated_identities_binding_id_unique"
    UNIQUE ("binding_id"),
  CONSTRAINT "federated_identities_issuer_user_unique"
    UNIQUE ("issuer", "user_id"),
  CONSTRAINT "federated_identities_user_id_users_id_fk"
    FOREIGN KEY ("user_id")
    REFERENCES "public"."users"("id")
    ON DELETE CASCADE,
  CONSTRAINT "federated_identities_issuer_length"
    CHECK (char_length("issuer") BETWEEN 1 AND 2048),
  CONSTRAINT "federated_identities_subject_length"
    CHECK (char_length("subject") BETWEEN 1 AND 1024)
);

CREATE INDEX "federated_identities_user_idx"
  ON "public"."federated_identities" ("user_id");
