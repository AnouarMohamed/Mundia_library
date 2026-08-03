import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { readMigrationFiles } from "drizzle-orm/migrator";
import pg from "pg";

const { Pool } = pg;

const apply = process.argv.includes("--apply");
const confirmed = process.argv.includes("--confirm-existing-schema");
if (apply && !confirmed) {
  throw new Error("--apply requires --confirm-existing-schema");
}
if (!process.env.DATABASE_URL) {
  throw new Error("DATABASE_URL is required");
}

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const migrationsFolder = path.join(
  repositoryRoot,
  "migrations",
  "postgres",
);
const journal = JSON.parse(
  fs.readFileSync(path.join(migrationsFolder, "meta", "_journal.json"), "utf8"),
);
const baselineEntries = journal.entries.filter((entry) => entry.idx <= 3);
const migrations = readMigrationFiles({ migrationsFolder }).slice(
  0,
  baselineEntries.length,
);
if (
  baselineEntries.length !== 4 ||
  migrations.length !== baselineEntries.length ||
  baselineEntries.some(
    (entry, index) => entry.when !== migrations[index]?.folderMillis,
  )
) {
  throw new Error("The reviewed legacy baseline no longer matches the journal");
}

const requiredColumns = {
  users: [
    "id",
    "full_name",
    "email",
    "university_id",
    "password",
    "university_card",
    "status",
    "role",
    "last_activity_date",
    "last_login",
    "created_at",
  ],
  books: [
    "id",
    "title",
    "author",
    "genre",
    "rating",
    "cover_url",
    "cover_color",
    "description",
    "total_copies",
    "available_copies",
    "video_url",
    "summary",
    "isbn",
    "publication_year",
    "publisher",
    "language",
    "page_count",
    "edition",
    "is_active",
    "updated_at",
    "updated_by",
    "created_at",
  ],
  borrow_records: [
    "id",
    "user_id",
    "book_id",
    "borrow_date",
    "due_date",
    "return_date",
    "status",
    "borrowed_by",
    "returned_by",
    "fine_amount",
    "notes",
    "renewal_count",
    "last_reminder_sent",
    "updated_at",
    "updated_by",
    "created_at",
  ],
  admin_requests: [
    "id",
    "user_id",
    "request_reason",
    "status",
    "reviewed_by",
    "reviewed_at",
    "rejection_reason",
    "created_at",
    "updated_at",
  ],
  book_reviews: [
    "id",
    "book_id",
    "user_id",
    "rating",
    "comment",
    "created_at",
    "updated_at",
  ],
  system_config: [
    "id",
    "key",
    "value",
    "description",
    "updated_at",
    "updated_by",
    "created_at",
  ],
};

const uuidColumns = {
  users: ["id"],
  books: ["id", "updated_by"],
  borrow_records: ["id", "user_id", "book_id"],
  book_reviews: ["id", "user_id", "book_id"],
  admin_requests: ["id", "user_id", "reviewed_by"],
  audit_logs: ["id", "user_id"],
  renewal_requests: ["id", "borrow_record_id", "user_id"],
  notifications: ["id", "user_id"],
  system_config: ["id"],
};

const foreignKeys = [
  ["books", "books_updated_by_users_id_fk", "updated_by", "users", "id"],
  [
    "borrow_records",
    "borrow_records_user_id_users_id_fk",
    "user_id",
    "users",
    "id",
  ],
  [
    "borrow_records",
    "borrow_records_book_id_books_id_fk",
    "book_id",
    "books",
    "id",
  ],
  [
    "book_reviews",
    "book_reviews_book_id_books_id_fk",
    "book_id",
    "books",
    "id",
  ],
  [
    "book_reviews",
    "book_reviews_user_id_users_id_fk",
    "user_id",
    "users",
    "id",
  ],
  [
    "admin_requests",
    "admin_requests_user_id_users_id_fk",
    "user_id",
    "users",
    "id",
  ],
  [
    "admin_requests",
    "admin_requests_reviewed_by_users_id_fk",
    "reviewed_by",
    "users",
    "id",
  ],
  ["audit_logs", "audit_logs_user_id_users_id_fk", "user_id", "users", "id"],
  [
    "renewal_requests",
    "renewal_requests_borrow_record_id_borrow_records_id_fk",
    "borrow_record_id",
    "borrow_records",
    "id",
  ],
  [
    "renewal_requests",
    "renewal_requests_user_id_users_id_fk",
    "user_id",
    "users",
    "id",
  ],
  [
    "notifications",
    "notifications_user_id_users_id_fk",
    "user_id",
    "users",
    "id",
  ],
];

const quoteIdentifier = (value) => {
  if (!/^[a-z][a-z0-9_]*$/.test(value)) {
    throw new Error("Unsafe SQL identifier in reviewed baseline definition");
  }
  return `"${value}"`;
};

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 1,
  connectionTimeoutMillis: 5_000,
});

const assertLegacySchema = async (client) => {
  const columns = await client.query(
    `select table_name, column_name
       from information_schema.columns
      where table_schema = 'public'
        and table_name = any($1::text[])`,
    [Object.keys(requiredColumns)],
  );
  const actual = new Map();
  for (const row of columns.rows) {
    const tableColumns = actual.get(row.table_name) ?? new Set();
    tableColumns.add(row.column_name);
    actual.set(row.table_name, tableColumns);
  }
  const missing = [];
  for (const [table, expectedColumns] of Object.entries(requiredColumns)) {
    for (const column of expectedColumns) {
      if (!actual.get(table)?.has(column)) missing.push(`${table}.${column}`);
    }
  }
  if (missing.length > 0) {
    throw new Error(`Existing schema is not baseline-compatible: ${missing.join(", ")}`);
  }

  const enums = await client.query(
    `select pg_type.typname, json_agg(pg_enum.enumlabel order by pg_enum.enumsortorder) as labels
       from pg_type
       join pg_enum on pg_enum.enumtypid = pg_type.oid
      where pg_type.typname = any($1::text[])
      group by pg_type.typname`,
    [[
      "borrow_status",
      "role",
      "status",
      "request_status",
      "user_role",
      "user_status",
    ]],
  );
  const actualEnums = new Map(enums.rows.map((row) => [row.typname, row.labels]));
  const authorizationLabels = ["PENDING", "APPROVED", "REJECTED"];
  if (
    JSON.stringify(actualEnums.get("borrow_status")) !==
    JSON.stringify(["PENDING", "BORROWED", "RETURNED"])
  ) {
    throw new Error("Existing enum borrow_status is not baseline-compatible");
  }

  const canonicalEnumLayout =
    JSON.stringify(actualEnums.get("role")) ===
      JSON.stringify(["USER", "ADMIN"]) &&
    JSON.stringify(actualEnums.get("status")) ===
      JSON.stringify(authorizationLabels);
  const legacyEnumLayout =
    JSON.stringify(actualEnums.get("user_role")) ===
      JSON.stringify(["USER", "ADMIN"]) &&
    JSON.stringify(actualEnums.get("user_status")) ===
      JSON.stringify(authorizationLabels) &&
    JSON.stringify(actualEnums.get("request_status")) ===
      JSON.stringify(authorizationLabels);
  if (canonicalEnumLayout === legacyEnumLayout) {
    throw new Error(
      "Expected exactly one canonical or reviewed legacy authorization enum layout",
    );
  }

  const enumColumns = await client.query(
    `select table_name, column_name, udt_name
       from information_schema.columns
      where table_schema = 'public'
        and (table_name, column_name) in (
          ('users', 'role'),
          ('users', 'status'),
          ('admin_requests', 'status'),
          ('renewal_requests', 'status')
        )`,
  );
  const actualColumnTypes = new Map(
    enumColumns.rows.map((row) => [
      `${row.table_name}.${row.column_name}`,
      row.udt_name,
    ]),
  );
  const expectedColumnTypes = legacyEnumLayout
    ? new Map([
        ["users.role", "user_role"],
        ["users.status", "user_status"],
        ["admin_requests.status", "request_status"],
        ["renewal_requests.status", "request_status"],
      ])
    : new Map([
        ["users.role", "role"],
        ["users.status", "status"],
        ["admin_requests.status", "status"],
        ["renewal_requests.status", "status"],
      ]);
  for (const [column, expectedType] of expectedColumnTypes) {
    if (actualColumnTypes.get(column) !== expectedType) {
      throw new Error(`Existing type for ${column} is not baseline-compatible`);
    }
  }

  const typedColumns = await client.query(
    `select table_name, column_name, udt_name
       from information_schema.columns
      where table_schema = 'public'
        and table_name = any($1::text[])`,
    [Object.keys(uuidColumns)],
  );
  const actualTypes = new Map(
    typedColumns.rows.map((row) => [
      `${row.table_name}.${row.column_name}`,
      row.udt_name,
    ]),
  );
  const declaredUuidColumns = Object.entries(uuidColumns).flatMap(
    ([table, columnsForTable]) =>
      columnsForTable.map((column) => `${table}.${column}`),
  );
  const canonicalUuidLayout = declaredUuidColumns.every(
    (column) => actualTypes.get(column) === "uuid",
  );
  const legacyUuidLayout = declaredUuidColumns.every((column) =>
    ["text", "varchar"].includes(actualTypes.get(column)),
  );
  if (canonicalUuidLayout === legacyUuidLayout) {
    throw new Error(
      "Expected exactly one canonical or reviewed legacy UUID column layout",
    );
  }

  if (legacyUuidLayout) {
    const uuidPattern =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";
    for (const [table, columnsForTable] of Object.entries(uuidColumns)) {
      for (const column of columnsForTable) {
        const invalid = await client.query(
          `select count(*)::int as count
             from public.${quoteIdentifier(table)}
            where ${quoteIdentifier(column)} is not null
              and ${quoteIdentifier(column)}::text !~* $1`,
          [uuidPattern],
        );
        if (invalid.rows[0]?.count !== 0) {
          throw new Error(
            `Existing values in ${table}.${column} are not UUID-compatible`,
          );
        }
      }
    }
  }

  return { legacyEnumLayout, legacyUuidLayout };
};

const normalizeLegacyEnums = async (client) => {
  await client.query("alter type public.user_role rename to role");
  await client.query("alter type public.user_status rename to status");
  for (const table of ["admin_requests", "renewal_requests"]) {
    await client.query(
      `alter table public.${table} alter column status drop default`,
    );
    await client.query(
      `alter table public.${table}
         alter column status type public.status
         using status::text::public.status`,
    );
    await client.query(
      `alter table public.${table}
         alter column status set default 'PENDING'::public.status`,
    );
  }
  await client.query("drop type public.request_status");
};

const normalizeLegacyUuidColumns = async (client) => {
  const affectedTables = Object.keys(uuidColumns);
  const constraints = await client.query(
    `select
       pg_class.relname as table_name,
       pg_constraint.conname,
       referenced.relname as referenced_table,
       to_json(array(
         select attribute.attname
           from unnest(pg_constraint.conkey) with ordinality as key(attnum, position)
           join pg_attribute attribute
             on attribute.attrelid = pg_constraint.conrelid
            and attribute.attnum = key.attnum
          order by key.position
       )) as columns,
       to_json(array(
         select attribute.attname
           from unnest(pg_constraint.confkey) with ordinality as key(attnum, position)
           join pg_attribute attribute
             on attribute.attrelid = pg_constraint.confrelid
            and attribute.attnum = key.attnum
          order by key.position
       )) as referenced_columns
       from pg_constraint
       join pg_class on pg_class.oid = pg_constraint.conrelid
       join pg_class referenced on referenced.oid = pg_constraint.confrelid
       join pg_namespace on pg_namespace.oid = pg_class.relnamespace
      where pg_constraint.contype = 'f'
        and pg_namespace.nspname = 'public'
        and pg_class.relname = any($1::text[])`,
    [affectedTables],
  );
  const expectedForeignKeySignatures = new Set(
    foreignKeys.map(
      ([table, , column, referencedTable, referencedColumn]) =>
        `${table}.${column}->${referencedTable}.${referencedColumn}`,
    ),
  );
  const actualForeignKeySignatures = new Set(
    constraints.rows.map(
      (constraint) =>
        `${constraint.table_name}.${constraint.columns.join(",")}->${constraint.referenced_table}.${constraint.referenced_columns.join(",")}`,
    ),
  );
  if (
    actualForeignKeySignatures.size !== expectedForeignKeySignatures.size ||
    [...actualForeignKeySignatures].some(
      (signature) => !expectedForeignKeySignatures.has(signature),
    )
  ) {
    throw new Error("Existing foreign keys are not baseline-compatible");
  }
  for (const constraint of constraints.rows) {
    await client.query(
      `alter table public.${quoteIdentifier(constraint.table_name)}
         drop constraint ${quoteIdentifier(constraint.conname)}`,
    );
  }

  for (const [table, columnsForTable] of Object.entries(uuidColumns)) {
    for (const column of columnsForTable) {
      await client.query(
        `alter table public.${quoteIdentifier(table)}
           alter column ${quoteIdentifier(column)} drop default`,
      );
      await client.query(
        `alter table public.${quoteIdentifier(table)}
           alter column ${quoteIdentifier(column)} type uuid
           using ${quoteIdentifier(column)}::uuid`,
      );
    }
    await client.query(
      `alter table public.${quoteIdentifier(table)}
         alter column "id" set default gen_random_uuid()`,
    );
  }
  await client.query(
    `alter table public.audit_logs
       alter column target_id type text using target_id::text`,
  );

  for (const [table, name, column, referencedTable, referencedColumn] of
    foreignKeys) {
    await client.query(
      `alter table public.${quoteIdentifier(table)}
         add constraint ${quoteIdentifier(name)}
         foreign key (${quoteIdentifier(column)})
         references public.${quoteIdentifier(referencedTable)} (${quoteIdentifier(referencedColumn)})
         on delete no action on update no action`,
    );
  }
};

try {
  const client = await pool.connect();
  try {
    await client.query("begin");
    await client.query("select pg_advisory_xact_lock(71202513)");
    const { legacyEnumLayout, legacyUuidLayout } =
      await assertLegacySchema(client);
    await client.query("create schema if not exists drizzle");
    await client.query(`
      create table if not exists drizzle.__drizzle_migrations (
        id serial primary key,
        hash text not null,
        created_at bigint
      )
    `);
    const ledger = await client.query(
      `select hash, created_at
         from drizzle.__drizzle_migrations
        order by created_at`,
    );
    const ledgerIsEmpty = ledger.rows.length === 0;
    const ledgerIsReviewedBaseline =
      ledger.rows.length === migrations.length &&
      ledger.rows.every(
        (row, index) =>
          row.hash === migrations[index]?.hash &&
          Number(row.created_at) === migrations[index]?.folderMillis,
      );
    if (!ledgerIsEmpty && !ledgerIsReviewedBaseline) {
      throw new Error(
        "Migration ledger is neither empty nor the exact reviewed baseline",
      );
    }

    if (apply) {
      if (legacyEnumLayout) await normalizeLegacyEnums(client);
      if (legacyUuidLayout) await normalizeLegacyUuidColumns(client);
      if (ledgerIsEmpty) {
        for (const migration of migrations) {
          await client.query(
            `insert into drizzle.__drizzle_migrations (hash, created_at)
             values ($1, $2)`,
            [migration.hash, migration.folderMillis],
          );
        }
      }
      await client.query("commit");
      console.log(
        `Verified ${migrations.length} reviewed baseline entries${
          legacyEnumLayout ? ", normalized legacy authorization enums" : ""
        }${legacyUuidLayout ? ", and normalized legacy UUID columns" : ""}.`,
      );
    } else {
      await client.query("rollback");
      console.log(
        `Baseline preflight passed for ${migrations.length} reviewed entries${
          legacyEnumLayout ? "; legacy authorization enums require normalization" : ""
        }${legacyUuidLayout ? "; legacy UUID columns require normalization" : ""}; no changes applied.`,
      );
    }
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }
} finally {
  await pool.end();
}
