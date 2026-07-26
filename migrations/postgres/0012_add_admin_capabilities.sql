-- ADMIN remains a prerequisite, but no longer acts as a blanket grant for
-- high-risk operations. This migration deliberately inserts no assignments:
-- production policy owners must explicitly grant each capability.

CREATE TABLE "public"."admin_capability_assignments" (
  "id" uuid DEFAULT gen_random_uuid() NOT NULL,
  "user_id" uuid NOT NULL,
  "capability" varchar(64) NOT NULL,
  "granted_by" uuid NOT NULL,
  "grant_reason" varchar(500) NOT NULL,
  "granted_at" timestamp with time zone DEFAULT now() NOT NULL,
  "expires_at" timestamp with time zone,
  "revoked_at" timestamp with time zone,
  "revoked_by" uuid,
  "revoke_reason" varchar(500),
  CONSTRAINT "admin_capability_assignments_pkey" PRIMARY KEY ("id"),
  CONSTRAINT "admin_capability_assignments_user_id_users_id_fk"
    FOREIGN KEY ("user_id")
    REFERENCES "public"."users"("id")
    ON DELETE RESTRICT,
  CONSTRAINT "admin_capability_assignments_granted_by_users_id_fk"
    FOREIGN KEY ("granted_by")
    REFERENCES "public"."users"("id")
    ON DELETE RESTRICT,
  CONSTRAINT "admin_capability_assignments_revoked_by_users_id_fk"
    FOREIGN KEY ("revoked_by")
    REFERENCES "public"."users"("id")
    ON DELETE RESTRICT,
  CONSTRAINT "admin_capability_assignments_capability_valid"
    CHECK ("capability" IN (
      'identity_evidence.read',
      'users.manage_status',
      'roles.manage_admin',
      'exports.read',
      'fines.manage_policy',
      'fines.recalculate',
      'automation.execute',
      'bulk.execute',
      'capabilities.manage'
    )),
  CONSTRAINT "admin_capability_assignments_grant_reason_valid"
    CHECK (char_length(btrim("grant_reason")) BETWEEN 10 AND 500),
  CONSTRAINT "admin_capability_assignments_expiry_valid"
    CHECK ("expires_at" IS NULL OR "expires_at" > "granted_at"),
  CONSTRAINT "admin_capability_assignments_revocation_valid"
    CHECK (
      (
        "revoked_at" IS NULL
        AND "revoked_by" IS NULL
        AND "revoke_reason" IS NULL
      ) OR (
        "revoked_at" IS NOT NULL
        AND "revoked_by" IS NOT NULL
        AND char_length(btrim("revoke_reason")) BETWEEN 10 AND 500
        AND "revoked_at" >= "granted_at"
      )
    )
);

CREATE UNIQUE INDEX "admin_capability_assignments_one_open_grant_idx"
  ON "public"."admin_capability_assignments" ("user_id", "capability")
  WHERE "revoked_at" IS NULL;

CREATE INDEX "admin_capability_assignments_user_lookup_idx"
  ON "public"."admin_capability_assignments"
  ("user_id", "capability", "expires_at");

CREATE OR REPLACE FUNCTION "public"."protect_admin_capability_assignment"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'admin capability assignments are append-only';
  END IF;

  IF
    OLD."user_id" IS DISTINCT FROM NEW."user_id"
    OR OLD."capability" IS DISTINCT FROM NEW."capability"
    OR OLD."granted_by" IS DISTINCT FROM NEW."granted_by"
    OR OLD."grant_reason" IS DISTINCT FROM NEW."grant_reason"
    OR OLD."granted_at" IS DISTINCT FROM NEW."granted_at"
    OR OLD."expires_at" IS DISTINCT FROM NEW."expires_at"
    OR OLD."revoked_at" IS NOT NULL
    OR NEW."revoked_at" IS NULL
    OR NEW."revoked_by" IS NULL
    OR NEW."revoke_reason" IS NULL
  THEN
    RAISE EXCEPTION
      'admin capability grants are immutable except for one audited revocation';
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER "admin_capability_assignments_protect_row"
  BEFORE UPDATE OR DELETE
  ON "public"."admin_capability_assignments"
  FOR EACH ROW
  EXECUTE FUNCTION "public"."protect_admin_capability_assignment"();

CREATE OR REPLACE FUNCTION "public"."reject_admin_capability_truncate"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'admin capability assignments are append-only';
END;
$$;

CREATE TRIGGER "admin_capability_assignments_reject_truncate"
  BEFORE TRUNCATE
  ON "public"."admin_capability_assignments"
  FOR EACH STATEMENT
  EXECUTE FUNCTION "public"."reject_admin_capability_truncate"();

CREATE OR REPLACE FUNCTION "public"."audit_admin_capability_assignment"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO "public"."audit_logs" (
    "user_id",
    "action",
    "target_id",
    "target_type",
    "details"
  )
  VALUES (
    CASE WHEN TG_OP = 'INSERT' THEN NEW."granted_by" ELSE NEW."revoked_by" END,
    CASE
      WHEN TG_OP = 'INSERT' THEN 'GRANT_ADMIN_CAPABILITY'
      ELSE 'REVOKE_ADMIN_CAPABILITY'
    END,
    NEW."user_id",
    'USER',
    jsonb_build_object(
      'assignmentId', NEW."id",
      'capability', NEW."capability",
      'grantReason', NEW."grant_reason",
      'expiresAt', NEW."expires_at",
      'revokeReason', NEW."revoke_reason"
    )::text
  );

  RETURN NEW;
END;
$$;

CREATE TRIGGER "admin_capability_assignments_audit"
  AFTER INSERT OR UPDATE
  ON "public"."admin_capability_assignments"
  FOR EACH ROW
  EXECUTE FUNCTION "public"."audit_admin_capability_assignment"();
