-- Rebuild instead of ALTER TYPE ... ADD VALUE because drizzle applies all
-- pending migrations in one transaction and PostgreSQL forbids consuming a
-- newly-added enum value before that transaction commits.
ALTER TYPE "public"."role" RENAME TO "role_before_super_admin";

CREATE TYPE "public"."role" AS ENUM ('USER', 'ADMIN', 'SUPER_ADMIN');

ALTER TABLE "public"."users"
  ALTER COLUMN "role" DROP DEFAULT,
  ALTER COLUMN "role" TYPE "public"."role"
    USING ("role"::text::"public"."role"),
  ALTER COLUMN "role" SET DEFAULT 'USER'::"public"."role";

DROP TYPE "public"."role_before_super_admin";
