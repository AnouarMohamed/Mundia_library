-- Preserve administrative access during the role-model upgrade. Prefer an
-- approved administrator who already holds an active role-governance grant;
-- otherwise select the oldest approved administrator deterministically.
DO $$
DECLARE
  bootstrap_user_id uuid;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM "public"."users"
    WHERE "role" = 'SUPER_ADMIN' AND "status" = 'APPROVED'
  ) THEN
    SELECT u."id"
      INTO bootstrap_user_id
      FROM "public"."users" u
      WHERE u."role" = 'ADMIN' AND u."status" = 'APPROVED'
      ORDER BY EXISTS (
        SELECT 1
        FROM "public"."admin_capability_assignments" a
        WHERE a."user_id" = u."id"
          AND a."capability" = 'roles.manage_admin'
          AND a."revoked_at" IS NULL
          AND (a."expires_at" IS NULL OR a."expires_at" > now())
      ) DESC,
      u."created_at" ASC NULLS LAST,
      u."id" ASC
      LIMIT 1;

    IF bootstrap_user_id IS NOT NULL THEN
      UPDATE "public"."users"
      SET "role" = 'SUPER_ADMIN'
      WHERE "id" = bootstrap_user_id;

      INSERT INTO "public"."audit_logs" (
        "user_id",
        "action",
        "target_id",
        "target_type",
        "details"
      ) VALUES (
        bootstrap_user_id,
        'BOOTSTRAP_SUPER_ADMIN',
        bootstrap_user_id::text,
        'USER',
        '{"source":"migration-0015","reason":"role-model-upgrade"}'
      );
    END IF;
  END IF;
END
$$;
