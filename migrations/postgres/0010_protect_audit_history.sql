-- Application audit history is append-only. Export to immutable external
-- retention remains a production platform responsibility, but no application
-- connection may rewrite or truncate the primary audit trail.

CREATE OR REPLACE FUNCTION "public"."reject_audit_log_mutation"()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'audit_logs is append-only';
END;
$$;

DROP TRIGGER IF EXISTS "audit_logs_reject_mutation" ON "public"."audit_logs";
CREATE TRIGGER "audit_logs_reject_mutation"
  BEFORE UPDATE OR DELETE OR TRUNCATE
  ON "public"."audit_logs"
  FOR EACH STATEMENT
  EXECUTE FUNCTION "public"."reject_audit_log_mutation"();
