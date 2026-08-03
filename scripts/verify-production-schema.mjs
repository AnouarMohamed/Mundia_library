import pg from "pg";

const { Pool } = pg;

const connectionString = process.env.DATABASE_URL;
if (!connectionString) {
  throw new Error("DATABASE_URL is required");
}

const pool = new Pool({
  connectionString,
  max: 1,
  connectionTimeoutMillis: 5_000,
});

const requiredTables = [
  "admin_capability_assignments",
  "admin_requests",
  "audit_logs",
  "book_reviews",
  "books",
  "borrow_records",
  "federated_identities",
  "notifications",
  "rate_limit_buckets",
  "renewal_requests",
  "system_config",
  "users",
];
const requiredUuidIdTables = requiredTables.filter(
  (table) => !["federated_identities", "rate_limit_buckets"].includes(table),
);

const requiredConstraints = [
  "books_available_copies_lte_total",
  "books_available_copies_nonnegative",
  "books_rating_range",
  "books_total_copies_nonnegative",
  "borrow_records_fine_amount_nonnegative",
  "borrow_records_lifecycle_valid",
  "borrow_records_renewal_count_nonnegative",
  "book_reviews_rating_range",
  "admin_capability_assignments_capability_valid",
  "admin_capability_assignments_expiry_valid",
  "admin_capability_assignments_grant_reason_valid",
  "admin_capability_assignments_revocation_valid",
  "federated_identities_binding_id_unique",
  "federated_identities_issuer_length",
  "federated_identities_issuer_user_unique",
  "federated_identities_subject_length",
  "rate_limit_buckets_identifier_hash_valid",
  "rate_limit_buckets_request_count_positive",
  "rate_limit_buckets_window_valid",
];

const requiredIndexes = [
  "admin_capability_assignments_one_open_grant_idx",
  "admin_capability_assignments_user_lookup_idx",
  "admin_requests_one_pending_per_user_idx",
  "book_reviews_one_per_user_book_idx",
  "borrow_records_one_active_per_user_book_idx",
  "federated_identities_user_idx",
  "notifications_user_read_created_idx",
  "rate_limit_buckets_expiry_idx",
  "renewal_requests_one_pending_per_loan_idx",
];

const assertContainsAll = (actual, expected, label) => {
  const missing = expected.filter((item) => !actual.has(item));
  if (missing.length > 0) {
    throw new Error(`Missing ${label}: ${missing.join(", ")}`);
  }
};

try {
  const tables = await pool.query(
    `select table_name
       from information_schema.tables
      where table_schema = 'public'
        and table_type = 'BASE TABLE'`,
  );
  assertContainsAll(
    new Set(tables.rows.map((row) => row.table_name)),
    requiredTables,
    "tables",
  );

  const constraints = await pool.query(
    `select conname
       from pg_constraint
      where connamespace = 'public'::regnamespace`,
  );
  assertContainsAll(
    new Set(constraints.rows.map((row) => row.conname)),
    requiredConstraints,
    "constraints",
  );

  const unvalidatedConstraints = await pool.query(
    `select conname
       from pg_constraint
      where connamespace = 'public'::regnamespace
        and conname = any($1::text[])
        and not convalidated`,
    [requiredConstraints],
  );
  if (unvalidatedConstraints.rows.length > 0) {
    throw new Error(
      `Unvalidated constraints: ${unvalidatedConstraints.rows
        .map((row) => row.conname)
        .join(", ")}`,
    );
  }

  const indexes = await pool.query(
    `select indexname
       from pg_indexes
      where schemaname = 'public'`,
  );
  assertContainsAll(
    new Set(indexes.rows.map((row) => row.indexname)),
    requiredIndexes,
    "indexes",
  );

  const auditTriggers = await pool.query(
    `select tgname
       from pg_trigger
      where tgrelid = 'public.audit_logs'::regclass
        and not tgisinternal`,
  );
  if (
    !auditTriggers.rows.some(
      (row) => row.tgname === "audit_logs_reject_mutation",
    )
  ) {
    throw new Error("audit_logs append-only trigger is missing");
  }

  const capabilityTriggers = await pool.query(
    `select tgname
       from pg_trigger
      where tgrelid = 'public.admin_capability_assignments'::regclass
        and not tgisinternal`,
  );
  const actualCapabilityTriggers = new Set(
    capabilityTriggers.rows.map((row) => row.tgname),
  );
  assertContainsAll(
    actualCapabilityTriggers,
    [
      "admin_capability_assignments_audit",
      "admin_capability_assignments_protect_row",
      "admin_capability_assignments_reject_truncate",
    ],
    "admin capability triggers",
  );

  const borrowStatuses = await pool.query(
    `select enumlabel
       from pg_enum
       join pg_type on pg_type.oid = pg_enum.enumtypid
      where pg_type.typname = 'borrow_status'
      order by enumsortorder`,
  );
  const actualStatuses = borrowStatuses.rows.map((row) => row.enumlabel);
  const expectedStatuses = ["PENDING", "BORROWED", "RETURNED"];
  if (JSON.stringify(actualStatuses) !== JSON.stringify(expectedStatuses)) {
    throw new Error(
      `Unexpected borrow_status values: ${actualStatuses.join(", ")}`,
    );
  }

  const nullableIdentityState = await pool.query(
    `select count(*)::int as count
       from information_schema.columns
      where table_schema = 'public'
        and table_name = 'users'
        and column_name in ('role', 'status')
        and is_nullable = 'YES'`,
  );
  if (nullableIdentityState.rows[0]?.count !== 0) {
    throw new Error("users.role and users.status must be NOT NULL");
  }

  const uuidColumns = await pool.query(
    `select table_name, column_name, data_type
       from information_schema.columns
      where table_schema = 'public'
        and column_name = 'id'
        and table_name = any($1::text[])`,
    [requiredUuidIdTables],
  );
  const nonUuidIds = uuidColumns.rows.filter((row) => row.data_type !== "uuid");
  if (
    uuidColumns.rows.length !== requiredUuidIdTables.length ||
    nonUuidIds.length > 0
  ) {
    throw new Error(
      `Every canonical table must use a UUID primary key; invalid tables: ${
        nonUuidIds.map((row) => row.table_name).join(", ") || "missing table ID"
      }`,
    );
  }

  const canonicalEnums = await pool.query(
    `select table_name, column_name, udt_name
       from information_schema.columns
      where table_schema = 'public'
        and (
          (table_name = 'users' and column_name in ('status', 'role'))
          or
          (table_name in ('admin_requests', 'renewal_requests') and column_name = 'status')
        )`,
  );
  const expectedEnumTypes = new Map([
    ["users.status", "status"],
    ["users.role", "role"],
    ["admin_requests.status", "status"],
    ["renewal_requests.status", "status"],
  ]);
  for (const row of canonicalEnums.rows) {
    const key = `${row.table_name}.${row.column_name}`;
    const expectedType = expectedEnumTypes.get(key);
    if (expectedType !== row.udt_name) {
      throw new Error(
        `Unexpected enum type for ${key}: ${row.udt_name}; expected ${expectedType}`,
      );
    }
    expectedEnumTypes.delete(key);
  }
  if (expectedEnumTypes.size > 0) {
    throw new Error(
      `Missing canonical enum columns: ${Array.from(expectedEnumTypes.keys()).join(", ")}`,
    );
  }

  const client = await pool.connect();
  try {
    await client.query("begin");
    await client.query("select 1");
    await client.query("rollback");

    let auditMutationRejected = false;
    await client.query("begin");
    try {
      // Statement-level trigger must reject even a mutation matching no rows.
      await client.query(
        "update public.audit_logs set action = action where false",
      );
    } catch (error) {
      auditMutationRejected =
        error?.code === "P0001" && /append-only/i.test(String(error?.message));
    } finally {
      await client.query("rollback");
    }
    if (!auditMutationRejected) {
      throw new Error("audit_logs accepted a forbidden update statement");
    }

    let capabilityTruncateRejected = false;
    await client.query("begin");
    try {
      await client.query(
        "truncate table public.admin_capability_assignments",
      );
    } catch (error) {
      capabilityTruncateRejected =
        error?.code === "P0001" &&
        /append-only/i.test(String(error?.message));
    } finally {
      await client.query("rollback");
    }
    if (!capabilityTruncateRejected) {
      throw new Error(
        "admin_capability_assignments accepted forbidden truncation",
      );
    }
  } finally {
    client.release();
  }

  console.log("Production schema verification passed.");
} finally {
  await pool.end();
}
