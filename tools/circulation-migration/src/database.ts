import { Client, type ClientConfig, type PoolClient } from "pg";
import { reconcile } from "./reconcile.js";
import { verifyPlan } from "./planner.js";
import {
  SNAPSHOT_SCHEMA,
  type LegacySnapshot,
  type MigrationPlan,
  type ReconciliationReport,
  type TargetCopy,
  type TargetFine,
  type TargetFineLedgerEntry,
  type TargetLoan,
} from "./types.js";

const LOCAL_HOSTS = new Set(["127.0.0.1", "[::1]"]);
const WRITE_ACK = "TARGET_ONLY_NO_DUAL_WRITE";
const INSERT_BATCH_SIZE = 500;

interface DatabaseIdentity {
  database: string;
  serverVersion: string;
  serverVersionNumber: number;
}

function asIso(value: string | Date | null): string | null {
  if (value === null) return null;
  return new Date(value).toISOString();
}

function asSafeInteger(value: string | number, field: string): number {
  const parsed =
    typeof value === "number" ? value : Number(value);
  if (!Number.isSafeInteger(parsed)) {
    throw new Error(`Target ${field} is outside JavaScript's exact integer range`);
  }
  return parsed;
}

export function assertLocalPostgresUrl(connectionString: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(connectionString);
  } catch {
    throw new TypeError("Database URL is not a valid URL");
  }
  if (!["postgres:", "postgresql:"].includes(parsed.protocol)) {
    throw new TypeError("Database URL must use postgres:// or postgresql://");
  }
  if (!LOCAL_HOSTS.has(parsed.hostname.toLowerCase())) {
    throw new TypeError(
      "Refusing database URL: only literal 127.0.0.1 or [::1] is permitted",
    );
  }
  if (parsed.search !== "" || parsed.hash !== "") {
    throw new TypeError(
      "Database URL query parameters and fragments are forbidden because they can create parsing ambiguity",
    );
  }
  if (parsed.username.length === 0) {
    throw new TypeError("Database URL must name an explicit least-privilege role");
  }
  if (decodeURIComponent(parsed.pathname).replace(/^\//, "") === "") {
    throw new TypeError("Database URL must name a database");
  }
  return parsed;
}

function databaseName(parsed: URL): string {
  return decodeURIComponent(parsed.pathname).replace(/^\//, "");
}

async function connect(
  connectionString: string,
  expectedDatabase: string,
  applicationName: string,
): Promise<Client> {
  const parsed = assertLocalPostgresUrl(connectionString);
  if (databaseName(parsed) !== expectedDatabase) {
    throw new Error(
      `URL database does not match --expect-database (${expectedDatabase})`,
    );
  }
  const config: ClientConfig = {
    host:
      parsed.hostname === "[::1]"
        ? "::1"
        : parsed.hostname,
    port: parsed.port === "" ? 5432 : Number(parsed.port),
    user: decodeURIComponent(parsed.username),
    password: decodeURIComponent(parsed.password),
    database: databaseName(parsed),
    application_name: applicationName,
    ssl: false,
    connectionTimeoutMillis: 5_000,
    query_timeout: 120_000,
  };
  const client = new Client(config);
  await client.connect();
  return client;
}

async function identity(
  client: Client | PoolClient,
): Promise<DatabaseIdentity> {
  const result = await client.query<{
    database: string;
    server_version: string;
    server_version_number: string;
  }>(`
    SELECT
      current_database() AS database,
      current_setting('server_version') AS server_version,
      current_setting('server_version_num') AS server_version_number
  `);
  const row = result.rows[0];
  if (!row) throw new Error("Database identity query returned no row");
  return {
    database: row.database,
    serverVersion: row.server_version,
    serverVersionNumber: Number(row.server_version_number),
  };
}

function assertExpectedIdentity(
  actual: DatabaseIdentity,
  expectedDatabase: string,
): void {
  if (actual.database !== expectedDatabase) {
    throw new Error(
      `Connected database ${actual.database} does not match expected ${expectedDatabase}`,
    );
  }
}

const LEGACY_SOURCE_COLUMNS = [
  "books.available_copies:integer:true",
  "books.created_at:timestamp with time zone:false",
  "books.id:uuid:true",
  "books.total_copies:integer:true",
  "books.updated_at:timestamp with time zone:false",
  "borrow_records.book_id:uuid:true",
  "borrow_records.borrow_date:timestamp with time zone:true",
  "borrow_records.borrowed_by:text:false",
  "borrow_records.created_at:timestamp with time zone:false",
  "borrow_records.due_date:date:false",
  "borrow_records.fine_amount:numeric(10,2):false",
  "borrow_records.id:uuid:true",
  "borrow_records.last_reminder_sent:timestamp with time zone:false",
  "borrow_records.notes:text:false",
  "borrow_records.renewal_count:integer:true",
  "borrow_records.return_date:date:false",
  "borrow_records.returned_by:text:false",
  "borrow_records.status:borrow_status:true",
  "borrow_records.updated_at:timestamp with time zone:false",
  "borrow_records.updated_by:text:false",
  "borrow_records.user_id:uuid:true",
].sort();

async function verifyLegacySchema(client: Client): Promise<void> {
  const expectedKeys = new Set(
    LEGACY_SOURCE_COLUMNS.map((column) => column.split(":", 1)[0]),
  );
  const columns = await client.query<{
    table_name: string;
    column_name: string;
    formatted_type: string;
    not_null: boolean;
  }>(`
    SELECT
      relation.relname AS table_name,
      attribute.attname AS column_name,
      pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) AS formatted_type,
      attribute.attnotnull AS not_null
    FROM pg_catalog.pg_attribute AS attribute
    JOIN pg_catalog.pg_class AS relation ON relation.oid = attribute.attrelid
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname = ANY($1::text[])
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped
  `, [["books", "borrow_records"]]);
  const actual = columns.rows
    .filter((row) =>
      expectedKeys.has(`${row.table_name}.${row.column_name}`),
    )
    .map(
      (row) =>
        `${row.table_name}.${row.column_name}:${row.formatted_type}:${String(row.not_null)}`,
    )
    .sort();
  if (
    actual.length !== LEGACY_SOURCE_COLUMNS.length ||
    actual.some((column, index) => column !== LEGACY_SOURCE_COLUMNS[index])
  ) {
    throw new Error(
      "Legacy source column signature differs from the reviewed snapshot contract",
    );
  }
  const statuses = await client.query<{ status: string }>(`
    SELECT enum_value.enumlabel AS status
    FROM pg_catalog.pg_enum AS enum_value
    WHERE enum_value.enumtypid = 'public.borrow_status'::regtype
    ORDER BY enum_value.enumsortorder
  `);
  if (
    statuses.rows.map((row) => row.status).join(",") !==
    "PENDING,BORROWED,RETURNED"
  ) {
    throw new Error(
      "Legacy borrow_status enum differs from the reviewed lifecycle contract",
    );
  }
}

export async function snapshotLegacy(options: {
  sourceUrl: string;
  expectedDatabase: string;
}): Promise<LegacySnapshot> {
  const client = await connect(
    options.sourceUrl,
    options.expectedDatabase,
    "mundia_circulation_snapshot",
  );
  try {
    await client.query(
      "BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE",
    );
    await client.query("SET LOCAL statement_timeout = '120s'");
    await client.query("SET LOCAL lock_timeout = '5s'");
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '5min'");
    await client.query(
      "LOCK TABLE public.books, public.borrow_records IN ACCESS SHARE MODE",
    );
    const actual = await identity(client);
    assertExpectedIdentity(actual, options.expectedDatabase);
    if (actual.serverVersionNumber < 180_000) {
      throw new Error(
        `Source PostgreSQL ${actual.serverVersion} is below the PostgreSQL 18 migration baseline`,
      );
    }
    await verifyLegacySchema(client);

    const captured = await client.query<{ captured_at: Date }>(
      "SELECT transaction_timestamp() AS captured_at",
    );
    const capturedAt = captured.rows[0]?.captured_at;
    if (!capturedAt) throw new Error("Could not capture snapshot timestamp");

    const books = await client.query<{
      id: string;
      total_copies: number;
      available_copies: number;
      created_at: Date | null;
      updated_at: Date | null;
    }>(`
      SELECT
        id::text,
        total_copies,
        available_copies,
        created_at,
        updated_at
      FROM public.books
      ORDER BY id
    `);

    const loans = await client.query<{
      id: string;
      user_id: string;
      book_id: string;
      borrow_date: Date;
      due_date: string | null;
      return_date: string | null;
      status: "PENDING" | "BORROWED" | "RETURNED";
      borrowed_by: string | null;
      returned_by: string | null;
      fine_amount: string | null;
      notes: string | null;
      renewal_count: number;
      last_reminder_sent: Date | null;
      updated_at: Date | null;
      updated_by: string | null;
      created_at: Date | null;
    }>(`
      SELECT
        id::text,
        user_id::text,
        book_id::text,
        borrow_date,
        due_date::text,
        return_date::text,
        status::text,
        borrowed_by,
        returned_by,
        fine_amount::text,
        notes,
        renewal_count,
        last_reminder_sent,
        updated_at,
        updated_by,
        created_at
      FROM public.borrow_records
      ORDER BY id
    `);

    await client.query("COMMIT");
    return {
      schemaVersion: SNAPSHOT_SCHEMA,
      capturedAt: capturedAt.toISOString(),
      source: {
        database: actual.database,
        serverVersion: actual.serverVersion,
        contractVersion: "legacy-circulation-source/pg18-v1",
        transactionIsolation: "SERIALIZABLE_READ_ONLY_DEFERRABLE",
      },
      books: books.rows.map((row) => ({
        id: row.id,
        totalCopies: row.total_copies,
        availableCopies: row.available_copies,
        createdAt: asIso(row.created_at),
        updatedAt: asIso(row.updated_at),
      })),
      borrowRecords: loans.rows.map((row) => ({
        id: row.id,
        userId: row.user_id,
        bookId: row.book_id,
        borrowDate: row.borrow_date.toISOString(),
        dueDate: row.due_date,
        returnDate: row.return_date,
        status: row.status,
        borrowedBy: row.borrowed_by,
        returnedBy: row.returned_by,
        fineAmount: row.fine_amount,
        notes: row.notes,
        renewalCount: row.renewal_count,
        lastReminderSent: asIso(row.last_reminder_sent),
        updatedAt: asIso(row.updated_at),
        updatedBy: row.updated_by,
        createdAt: asIso(row.created_at),
      })),
    };
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    await client.end();
  }
}

const TARGET_PHASE2_COLUMNS = [
  "circulation_policy_current.revision_id:uuid:true",
  "circulation_policy_current.singleton:boolean:true",
  "circulation_policy_idempotency.completed_at:timestamp with time zone:false",
  "circulation_policy_idempotency.created_at:timestamp with time zone:true",
  "circulation_policy_idempotency.expires_at:timestamp with time zone:true",
  "circulation_policy_idempotency.idempotency_key:character varying(128):true",
  "circulation_policy_idempotency.owner_fingerprint:character(64):true",
  "circulation_policy_idempotency.request_fingerprint:character(64):true",
  "circulation_policy_idempotency.revision_id:uuid:false",
  "circulation_policy_revision.actor_fingerprint:character(64):true",
  "circulation_policy_revision.created_at:timestamp with time zone:true",
  "circulation_policy_revision.default_loan_period_seconds:bigint:true",
  "circulation_policy_revision.effective_at:timestamp with time zone:true",
  "circulation_policy_revision.fine_currency:character(3):true",
  "circulation_policy_revision.maximum_active_reservations:integer:true",
  "circulation_policy_revision.maximum_renewals:integer:true",
  "circulation_policy_revision.renewal_period_seconds:bigint:true",
  "circulation_policy_revision.reservation_hold_period_seconds:bigint:true",
  "circulation_policy_revision.revision_id:uuid:true",
  "circulation_policy_revision.sequence:bigint:true",
  "circulation_rate_limit_bucket.bucket_key:character varying(32):true",
  "circulation_rate_limit_bucket.expires_at:timestamp with time zone:true",
  "circulation_rate_limit_bucket.principal_fingerprint:character(64):true",
  "circulation_rate_limit_bucket.request_count:integer:true",
  "circulation_rate_limit_bucket.window_started_at:timestamp with time zone:true",
  "circulation_reservation.cancelled_at:timestamp with time zone:false",
  "circulation_reservation.copy_id:uuid:false",
  "circulation_reservation.created_at:timestamp with time zone:true",
  "circulation_reservation.edition_id:uuid:true",
  "circulation_reservation.expires_at:timestamp with time zone:false",
  "circulation_reservation.fulfilled_at:timestamp with time zone:false",
  "circulation_reservation.id:uuid:true",
  "circulation_reservation.member_id:uuid:true",
  "circulation_reservation.placed_at:timestamp with time zone:true",
  "circulation_reservation.ready_at:timestamp with time zone:false",
  "circulation_reservation.status:character varying(16):true",
  "circulation_reservation.updated_at:timestamp with time zone:true",
  "circulation_reservation.version:bigint:true",
  "circulation_reservation_idempotency.cancelled_at:timestamp with time zone:false",
  "circulation_reservation_idempotency.completed_at:timestamp with time zone:false",
  "circulation_reservation_idempotency.copy_id:uuid:false",
  "circulation_reservation_idempotency.created_at:timestamp with time zone:true",
  "circulation_reservation_idempotency.edition_id:uuid:false",
  "circulation_reservation_idempotency.expires_at:timestamp with time zone:true",
  "circulation_reservation_idempotency.expires_at_result:timestamp with time zone:false",
  "circulation_reservation_idempotency.fulfilled_at:timestamp with time zone:false",
  "circulation_reservation_idempotency.idempotency_key:character varying(128):true",
  "circulation_reservation_idempotency.member_id:uuid:false",
  "circulation_reservation_idempotency.operation:character varying(24):true",
  "circulation_reservation_idempotency.owner_fingerprint:character(64):true",
  "circulation_reservation_idempotency.placed_at:timestamp with time zone:false",
  "circulation_reservation_idempotency.ready_at:timestamp with time zone:false",
  "circulation_reservation_idempotency.request_fingerprint:character(64):true",
  "circulation_reservation_idempotency.reservation_id:uuid:false",
  "circulation_reservation_idempotency.reservation_status:character varying(16):false",
  "circulation_reservation_idempotency.reservation_version:bigint:false",
  "circulation_reservation_idempotency.response_status:integer:false",
  "circulation_copy.barcode:character varying(64):true",
  "circulation_copy.branch_id:uuid:true",
  "circulation_copy.created_at:timestamp with time zone:true",
  "circulation_copy.edition_id:uuid:true",
  "circulation_copy.id:uuid:true",
  "circulation_copy.shelf_location:character varying(128):false",
  "circulation_copy.status:character varying(32):true",
  "circulation_copy.updated_at:timestamp with time zone:true",
  "circulation_copy.version:bigint:true",
  "circulation_consumer_inbox.aggregate_id:uuid:true",
  "circulation_consumer_inbox.aggregate_type:character varying(100):true",
  "circulation_consumer_inbox.aggregate_version:bigint:true",
  "circulation_consumer_inbox.consumer_name:character varying(100):true",
  "circulation_consumer_inbox.disposition:character varying(32):true",
  "circulation_consumer_inbox.event_id:uuid:true",
  "circulation_consumer_inbox.event_type:character varying(160):true",
  "circulation_consumer_inbox.event_version:integer:true",
  "circulation_consumer_inbox.payload_sha256:character(64):true",
  "circulation_consumer_inbox.processed_at:timestamp with time zone:true",
  "circulation_consumer_inbox.received_at:timestamp with time zone:true",
  "circulation_inventory_audit_entry.actor_fingerprint:character(64):true",
  "circulation_inventory_audit_entry.barcode:character varying(64):true",
  "circulation_inventory_audit_entry.branch_id:uuid:true",
  "circulation_inventory_audit_entry.copy_id:uuid:true",
  "circulation_inventory_audit_entry.copy_status:character varying(32):true",
  "circulation_inventory_audit_entry.copy_version:bigint:true",
  "circulation_inventory_audit_entry.created_at:timestamp with time zone:true",
  "circulation_inventory_audit_entry.edition_id:uuid:true",
  "circulation_inventory_audit_entry.id:uuid:true",
  "circulation_inventory_audit_entry.occurred_at:timestamp with time zone:true",
  "circulation_inventory_audit_entry.operation:character varying(32):true",
  "circulation_inventory_audit_entry.previous_branch_id:uuid:false",
  "circulation_inventory_audit_entry.previous_shelf_location:character varying(128):false",
  "circulation_inventory_audit_entry.previous_status:character varying(32):false",
  "circulation_inventory_audit_entry.reason:character varying(500):true",
  "circulation_inventory_audit_entry.shelf_location:character varying(128):false",
  "circulation_fine.balance_minor:bigint:true",
  "circulation_fine.created_at:timestamp with time zone:true",
  "circulation_fine.currency:character(3):true",
  "circulation_fine.id:uuid:true",
  "circulation_fine.loan_id:uuid:true",
  "circulation_fine.member_id:uuid:true",
  "circulation_fine.status:character varying(16):true",
  "circulation_fine.updated_at:timestamp with time zone:true",
  "circulation_fine.version:bigint:true",
  "circulation_fine_ledger_entry.actor_fingerprint:character(64):true",
  "circulation_fine_ledger_entry.created_at:timestamp with time zone:true",
  "circulation_fine_ledger_entry.delta_minor:bigint:true",
  "circulation_fine_ledger_entry.entry_type:character varying(16):true",
  "circulation_fine_ledger_entry.external_reference:character varying(128):false",
  "circulation_fine_ledger_entry.fine_id:uuid:true",
  "circulation_fine_ledger_entry.fine_version:bigint:true",
  "circulation_fine_ledger_entry.id:uuid:true",
  "circulation_fine_ledger_entry.occurred_at:timestamp with time zone:true",
  "circulation_fine_ledger_entry.reason:character varying(500):false",
  "circulation_loan.checked_out_at:timestamp with time zone:false",
  "circulation_loan.copy_id:uuid:false",
  "circulation_loan.created_at:timestamp with time zone:true",
  "circulation_loan.due_at:timestamp with time zone:false",
  "circulation_loan.edition_id:uuid:true",
  "circulation_loan.id:uuid:true",
  "circulation_loan.member_id:uuid:true",
  "circulation_loan.rejected_at:timestamp with time zone:false",
  "circulation_loan.renewal_count:integer:true",
  "circulation_loan.requested_at:timestamp with time zone:true",
  "circulation_loan.returned_at:timestamp with time zone:false",
  "circulation_loan.status:character varying(32):true",
  "circulation_loan.updated_at:timestamp with time zone:true",
  "circulation_loan.version:bigint:true",
  "circulation_member_eligibility.created_at:timestamp with time zone:true",
  "circulation_member_eligibility.member_id:uuid:true",
  "circulation_member_eligibility.reason_code:character varying(64):false",
  "circulation_member_eligibility.source_occurred_at:timestamp with time zone:true",
  "circulation_member_eligibility.source_version:bigint:true",
  "circulation_member_eligibility.status:character varying(32):true",
  "circulation_member_eligibility.updated_at:timestamp with time zone:true",
  "outbox_event.aggregate_id:uuid:true",
  "outbox_event.aggregate_type:character varying(100):true",
  "outbox_event.aggregate_version:bigint:true",
  "outbox_event.blocked_at:timestamp with time zone:false",
  "outbox_event.broker_offset:bigint:false",
  "outbox_event.broker_partition:integer:false",
  "outbox_event.broker_topic:character varying(249):false",
  "outbox_event.created_at:timestamp with time zone:true",
  "outbox_event.delivery_attempts:integer:true",
  "outbox_event.event_type:character varying(160):true",
  "outbox_event.event_version:integer:true",
  "outbox_event.headers:jsonb:true",
  "outbox_event.id:uuid:true",
  "outbox_event.last_attempt_at:timestamp with time zone:false",
  "outbox_event.last_error_code:character varying(64):false",
  "outbox_event.lease_expires_at:timestamp with time zone:false",
  "outbox_event.lease_owner:character varying(100):false",
  "outbox_event.lease_token:uuid:false",
  "outbox_event.next_attempt_at:timestamp with time zone:true",
  "outbox_event.occurred_at:timestamp with time zone:true",
  "outbox_event.payload:jsonb:true",
  "outbox_event.published_at:timestamp with time zone:false",
  "outbox_event.trace_id:character varying(64):false",
].sort();

const REQUIRED_TARGET_CONSTRAINTS = new Set([
  "circulation_policy_current_pkey",
  "circulation_policy_current_revision_id_fkey",
  "circulation_policy_revision_pkey",
  "circulation_policy_revision_sequence_key",
  "circulation_reservation_pkey",
  "circulation_reservation_copy_id_fkey",
  "circulation_reservation_idempotency_pkey",
  "circulation_rate_limit_bucket_pkey",
  "ck_circulation_policy_sequence",
  "ck_circulation_policy_loan_period",
  "ck_circulation_policy_renewal_period",
  "ck_circulation_policy_maximum_renewals",
  "ck_circulation_policy_currency",
  "ck_circulation_policy_hold_period",
  "ck_circulation_policy_maximum_reservations",
  "ck_circulation_policy_actor",
  "ck_circulation_policy_timestamps",
  "ck_circulation_policy_singleton",
  "ck_circulation_policy_idempotency_owner",
  "ck_circulation_policy_idempotency_key",
  "ck_circulation_policy_idempotency_fingerprint",
  "ck_circulation_policy_idempotency_expiry",
  "ck_circulation_policy_idempotency_completion",
  "ck_circulation_reservation_status",
  "ck_circulation_reservation_version",
  "ck_circulation_reservation_timestamps",
  "ck_circulation_reservation_state",
  "ck_circulation_reservation_idempotency_owner",
  "ck_circulation_reservation_idempotency_key",
  "ck_circulation_reservation_idempotency_operation",
  "ck_circulation_reservation_idempotency_fingerprint",
  "ck_circulation_reservation_idempotency_response",
  "ck_circulation_reservation_idempotency_expiry",
  "ck_circulation_reservation_idempotency_completion",
  "ck_circulation_rate_limit_principal",
  "ck_circulation_rate_limit_bucket_key",
  "ck_circulation_rate_limit_count",
  "ck_circulation_rate_limit_window",
  "ck_circulation_copy_barcode_shape",
  "ck_circulation_copy_shelf_location_shape",
  "uq_circulation_inventory_audit_version",
  "ck_circulation_inventory_audit_version",
  "ck_circulation_inventory_audit_operation",
  "ck_circulation_inventory_audit_status",
  "ck_circulation_inventory_audit_actor",
  "ck_circulation_inventory_audit_reason",
  "ck_circulation_inventory_audit_shape",
  "ck_circulation_inventory_audit_timestamps",
  "circulation_inventory_audit_entry_copy_id_fkey",
  "ck_circulation_loan_renewal_count",
  "ck_circulation_fine_currency",
  "ck_circulation_fine_balance",
  "ck_circulation_fine_status",
  "ck_circulation_fine_state",
  "ck_circulation_fine_version",
  "ck_circulation_fine_timestamps",
  "circulation_fine_loan_id_fkey",
  "uq_circulation_fine_ledger_version",
  "ck_circulation_fine_ledger_version",
  "ck_circulation_fine_ledger_type",
  "ck_circulation_fine_ledger_delta",
  "ck_circulation_fine_ledger_actor",
  "ck_circulation_fine_ledger_shape",
  "ck_circulation_fine_ledger_timestamps",
  "circulation_fine_ledger_entry_fine_id_fkey",
  "ck_outbox_delivery_attempts",
  "ck_outbox_delivery_lease",
  "ck_outbox_delivery_publication",
  "ck_outbox_delivery_blocked",
  "ck_outbox_delivery_error_code",
  "circulation_consumer_inbox_pkey",
  "uq_circulation_consumer_inbox_aggregate_version",
  "ck_circulation_consumer_inbox_consumer_name",
  "ck_circulation_consumer_inbox_event_type",
  "ck_circulation_consumer_inbox_event_version",
  "ck_circulation_consumer_inbox_aggregate_type",
  "ck_circulation_consumer_inbox_aggregate_version",
  "ck_circulation_consumer_inbox_payload_sha256",
  "ck_circulation_consumer_inbox_disposition",
  "ck_circulation_consumer_inbox_timestamps",
  "circulation_member_eligibility_pkey",
  "ck_circulation_member_eligibility_status",
  "ck_circulation_member_eligibility_reason",
  "ck_circulation_member_eligibility_reason_shape",
  "ck_circulation_member_eligibility_source_version",
  "ck_circulation_member_eligibility_timestamps",
]);
const TARGET_FLYWAY_CHECKSUMS = [
  1_823_238_944,
  1_736_558_870,
  1_710_092_712,
  424_313_616,
  2_132_266_084,
  136_632_738,
  -907_354_976,
  -1_922_605_307,
  740_925_932,
  1_483_066_326,
  -1_841_717_286,
  2_036_232_323,
] as const;

async function verifyTargetSchema(client: Client): Promise<void> {
  const historyTable = await client.query<{ table_name: string | null }>(
    "SELECT to_regclass('public.flyway_schema_history')::text AS table_name",
  );
  if (!historyTable.rows[0]?.table_name) {
    throw new Error(
      "Target Flyway history is absent; only the reviewed circulation phase-2 schema is accepted",
    );
  }
  const history = await client.query<{
    version: string;
    checksum: number | null;
    success: boolean;
  }>(`
    SELECT version::text, checksum, success
    FROM public.flyway_schema_history
    WHERE version IS NOT NULL
    ORDER BY installed_rank
  `);
  if (
    history.rows.length !== TARGET_FLYWAY_CHECKSUMS.length ||
    history.rows.some(
      (row, index) =>
        row.version !== String(index + 1) ||
        row.checksum !== TARGET_FLYWAY_CHECKSUMS[index] ||
        row.success !== true,
    )
  ) {
    throw new Error(
      "Target Flyway history must contain the exact reviewed checksums for successful versions 1 through 12",
    );
  }

  const columns = await client.query<{
    table_name: string;
    column_name: string;
    formatted_type: string;
    not_null: boolean;
  }>(`
    SELECT
      relation.relname AS table_name,
      attribute.attname AS column_name,
      pg_catalog.format_type(attribute.atttypid, attribute.atttypmod) AS formatted_type,
      attribute.attnotnull AS not_null
    FROM pg_catalog.pg_attribute AS attribute
    JOIN pg_catalog.pg_class AS relation
      ON relation.oid = attribute.attrelid
    JOIN pg_catalog.pg_namespace AS namespace
      ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname = ANY($1::text[])
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped
    ORDER BY relation.relname, attribute.attname
  `, [
    [
      "circulation_copy",
      "circulation_inventory_audit_entry",
      "circulation_loan",
      "circulation_fine",
      "circulation_fine_ledger_entry",
      "circulation_consumer_inbox",
      "circulation_member_eligibility",
      "circulation_policy_current",
      "circulation_policy_idempotency",
      "circulation_policy_revision",
      "circulation_rate_limit_bucket",
      "circulation_reservation",
      "circulation_reservation_idempotency",
      "outbox_event",
    ],
  ]);
  const actualColumns = columns.rows
    .map(
      (row) =>
        `${row.table_name}.${row.column_name}:${row.formatted_type}:${String(row.not_null)}`,
    )
    .sort();
  if (
    actualColumns.length !== TARGET_PHASE2_COLUMNS.length ||
    actualColumns.some(
      (column, index) => column !== TARGET_PHASE2_COLUMNS[index],
    )
  ) {
    throw new Error(
      "Target circulation table signature differs from the reviewed phase-2 contract",
    );
  }

  const constraints = await client.query<{
    constraint_name: string;
    validated: boolean;
  }>(`
    SELECT target_constraint.conname AS constraint_name, target_constraint.convalidated AS validated
    FROM pg_catalog.pg_constraint AS target_constraint
    JOIN pg_catalog.pg_class AS relation
      ON relation.oid = target_constraint.conrelid
    JOIN pg_catalog.pg_namespace AS namespace
      ON namespace.oid = relation.relnamespace
    WHERE namespace.nspname = 'public'
      AND relation.relname = ANY($1::text[])
  `, [
    [
      "circulation_copy",
      "circulation_inventory_audit_entry",
      "circulation_loan",
      "circulation_fine",
      "circulation_fine_ledger_entry",
      "circulation_consumer_inbox",
      "circulation_member_eligibility",
      "circulation_policy_current",
      "circulation_policy_idempotency",
      "circulation_policy_revision",
      "circulation_rate_limit_bucket",
      "circulation_reservation",
      "circulation_reservation_idempotency",
      "outbox_event",
    ],
  ]);
  const validConstraintNames = new Set(
    constraints.rows
      .filter((row) => row.validated)
      .map((row) => row.constraint_name),
  );
  for (const name of REQUIRED_TARGET_CONSTRAINTS) {
    if (!validConstraintNames.has(name)) {
      throw new Error(
        `Target circulation phase-2 constraint is absent or unvalidated: ${name}`,
      );
    }
  }

  const triggers = await client.query<{
    trigger_name: string;
    enabled: string;
    function_name: string;
  }>(`
    SELECT
      trigger.tgname AS trigger_name,
      trigger.tgenabled AS enabled,
      procedure.proname AS function_name
    FROM pg_catalog.pg_trigger AS trigger
    JOIN pg_catalog.pg_class AS relation ON relation.oid = trigger.tgrelid
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
    JOIN pg_catalog.pg_proc AS procedure ON procedure.oid = trigger.tgfoid
    WHERE namespace.nspname = 'public'
      AND NOT trigger.tgisinternal
      AND trigger.tgname = ANY($1::text[])
  `, [
    [
      "trg_circulation_fine_ledger_no_update_delete",
      "trg_circulation_fine_ledger_no_truncate",
      "trg_circulation_fine_protect_identity",
      "trg_circulation_fine_ledger_consistency_from_fine",
      "trg_circulation_fine_ledger_consistency_from_entry",
      "trg_circulation_inventory_audit_no_update_delete",
      "trg_circulation_inventory_audit_no_truncate",
      "trg_circulation_consumer_inbox_no_update_or_delete",
      "trg_circulation_consumer_inbox_no_truncate",
      "trg_circulation_member_eligibility_guard_update_or_delete",
      "trg_circulation_member_eligibility_no_truncate",
      "trg_circulation_policy_revision_no_update_delete",
      "trg_circulation_policy_revision_no_truncate",
    ],
  ]);
  const expectedTriggers = new Map([
    [
      "trg_circulation_fine_ledger_no_update_delete",
      "reject_circulation_fine_ledger_mutation",
    ],
    [
      "trg_circulation_fine_ledger_no_truncate",
      "reject_circulation_fine_ledger_mutation",
    ],
    [
      "trg_circulation_fine_protect_identity",
      "protect_circulation_fine_identity",
    ],
    [
      "trg_circulation_fine_ledger_consistency_from_fine",
      "validate_circulation_fine_ledger_consistency",
    ],
    [
      "trg_circulation_fine_ledger_consistency_from_entry",
      "validate_circulation_fine_ledger_consistency",
    ],
    [
      "trg_circulation_inventory_audit_no_update_delete",
      "reject_circulation_inventory_audit_mutation",
    ],
    [
      "trg_circulation_inventory_audit_no_truncate",
      "reject_circulation_inventory_audit_mutation",
    ],
    [
      "trg_circulation_consumer_inbox_no_update_or_delete",
      "reject_circulation_consumer_inbox_mutation",
    ],
    [
      "trg_circulation_consumer_inbox_no_truncate",
      "reject_circulation_consumer_inbox_mutation",
    ],
    [
      "trg_circulation_member_eligibility_guard_update_or_delete",
      "guard_circulation_member_eligibility_mutation",
    ],
    [
      "trg_circulation_member_eligibility_no_truncate",
      "reject_circulation_member_eligibility_truncate",
    ],
    [
      "trg_circulation_policy_revision_no_update_delete",
      "protect_circulation_policy_revision",
    ],
    [
      "trg_circulation_policy_revision_no_truncate",
      "protect_circulation_policy_revision",
    ],
  ]);
  for (const [name, functionName] of expectedTriggers) {
    const trigger = triggers.rows.find((row) => row.trigger_name === name);
    if (trigger?.enabled !== "O" || trigger.function_name !== functionName) {
      throw new Error(
        `Target circulation phase-2 integrity trigger is absent or disabled: ${name}`,
      );
    }
  }
}

async function lockTargetSchema(client: Client): Promise<void> {
  await client.query(`
    LOCK TABLE
      public.flyway_schema_history,
      public.circulation_copy,
      public.circulation_loan,
      public.circulation_fine,
      public.circulation_fine_ledger_entry,
      public.circulation_consumer_inbox,
      public.circulation_member_eligibility,
      public.circulation_policy_current,
      public.circulation_policy_idempotency,
      public.circulation_policy_revision,
      public.circulation_rate_limit_bucket,
      public.circulation_reservation,
      public.circulation_reservation_idempotency,
      public.outbox_event
    IN ACCESS SHARE MODE
  `);
}

function chunks<T>(rows: T[]): T[][] {
  const result: T[][] = [];
  for (let offset = 0; offset < rows.length; offset += INSERT_BATCH_SIZE) {
    result.push(rows.slice(offset, offset + INSERT_BATCH_SIZE));
  }
  return result;
}

async function insertCopies(
  client: Client,
  copies: TargetCopy[],
): Promise<number> {
  let inserted = 0;
  for (const batch of chunks(copies)) {
    const result = await client.query(
      `
        INSERT INTO public.circulation_copy (
          id,
          edition_id,
          branch_id,
          barcode,
          status,
          shelf_location,
          version,
          created_at,
          updated_at
        )
        SELECT
          row.id,
          row.edition_id,
          row.branch_id,
          row.barcode,
          row.status,
          row.shelf_location,
          row.version,
          row.created_at,
          row.updated_at
        FROM jsonb_to_recordset($1::jsonb) AS row(
          id uuid,
          edition_id uuid,
          branch_id uuid,
          barcode varchar(64),
          status varchar(32),
          shelf_location varchar(128),
          version bigint,
          created_at timestamptz,
          updated_at timestamptz
        )
        ON CONFLICT (id) DO NOTHING
      `,
      [
        JSON.stringify(
          batch.map((copy) => ({
            id: copy.id,
            edition_id: copy.editionId,
            branch_id: copy.branchId,
            barcode: copy.barcode,
            status: copy.status,
            shelf_location: copy.shelfLocation,
            version: copy.version,
            created_at: copy.createdAt,
            updated_at: copy.updatedAt,
          })),
        ),
      ],
    );
    inserted += result.rowCount ?? 0;
  }
  return inserted;
}

async function insertLoans(
  client: Client,
  loans: TargetLoan[],
): Promise<number> {
  let inserted = 0;
  for (const batch of chunks(loans)) {
    const result = await client.query(
      `
        INSERT INTO public.circulation_loan (
          id,
          member_id,
          edition_id,
          copy_id,
          status,
          requested_at,
          checked_out_at,
          due_at,
          returned_at,
          rejected_at,
          version,
          renewal_count,
          created_at,
          updated_at
        )
        SELECT
          row.id,
          row.member_id,
          row.edition_id,
          row.copy_id,
          row.status,
          row.requested_at,
          row.checked_out_at,
          row.due_at,
          row.returned_at,
          row.rejected_at,
          row.version,
          row.renewal_count,
          row.created_at,
          row.updated_at
        FROM jsonb_to_recordset($1::jsonb) AS row(
          id uuid,
          member_id uuid,
          edition_id uuid,
          copy_id uuid,
          status varchar(32),
          requested_at timestamptz,
          checked_out_at timestamptz,
          due_at timestamptz,
          returned_at timestamptz,
          rejected_at timestamptz,
          version bigint,
          renewal_count integer,
          created_at timestamptz,
          updated_at timestamptz
        )
        ON CONFLICT (id) DO NOTHING
      `,
      [
        JSON.stringify(
          batch.map((loan) => ({
            id: loan.id,
            member_id: loan.memberId,
            edition_id: loan.editionId,
            copy_id: loan.copyId,
            status: loan.status,
            requested_at: loan.requestedAt,
            checked_out_at: loan.checkedOutAt,
            due_at: loan.dueAt,
            returned_at: loan.returnedAt,
            rejected_at: loan.rejectedAt,
            version: loan.version,
            renewal_count: loan.renewalCount,
            created_at: loan.createdAt,
            updated_at: loan.updatedAt,
          })),
        ),
      ],
    );
    inserted += result.rowCount ?? 0;
  }
  return inserted;
}

async function insertFines(
  client: Client,
  fines: TargetFine[],
): Promise<number> {
  let inserted = 0;
  for (const batch of chunks(fines)) {
    const result = await client.query(
      `
        INSERT INTO public.circulation_fine (
          id,
          loan_id,
          member_id,
          currency,
          balance_minor,
          status,
          version,
          created_at,
          updated_at
        )
        SELECT
          row.id,
          row.loan_id,
          row.member_id,
          row.currency,
          row.balance_minor,
          row.status,
          row.version,
          row.created_at,
          row.updated_at
        FROM jsonb_to_recordset($1::jsonb) AS row(
          id uuid,
          loan_id uuid,
          member_id uuid,
          currency char(3),
          balance_minor bigint,
          status varchar(16),
          version bigint,
          created_at timestamptz,
          updated_at timestamptz
        )
        ON CONFLICT (id) DO NOTHING
      `,
      [
        JSON.stringify(
          batch.map((fine) => ({
            id: fine.id,
            loan_id: fine.loanId,
            member_id: fine.memberId,
            currency: fine.currency,
            balance_minor: fine.balanceMinor,
            status: fine.status,
            version: fine.version,
            created_at: fine.createdAt,
            updated_at: fine.updatedAt,
          })),
        ),
      ],
    );
    inserted += result.rowCount ?? 0;
  }
  return inserted;
}

async function insertFineLedgerEntries(
  client: Client,
  entries: TargetFineLedgerEntry[],
): Promise<number> {
  let inserted = 0;
  for (const batch of chunks(entries)) {
    const result = await client.query(
      `
        INSERT INTO public.circulation_fine_ledger_entry (
          id,
          fine_id,
          fine_version,
          entry_type,
          delta_minor,
          actor_fingerprint,
          reason,
          external_reference,
          occurred_at,
          created_at
        )
        SELECT
          row.id,
          row.fine_id,
          row.fine_version,
          row.entry_type,
          row.delta_minor,
          row.actor_fingerprint,
          row.reason,
          row.external_reference,
          row.occurred_at,
          row.created_at
        FROM jsonb_to_recordset($1::jsonb) AS row(
          id uuid,
          fine_id uuid,
          fine_version bigint,
          entry_type varchar(16),
          delta_minor bigint,
          actor_fingerprint char(64),
          reason varchar(500),
          external_reference varchar(128),
          occurred_at timestamptz,
          created_at timestamptz
        )
        ON CONFLICT (id) DO NOTHING
      `,
      [
        JSON.stringify(
          batch.map((entry) => ({
            id: entry.id,
            fine_id: entry.fineId,
            fine_version: entry.fineVersion,
            entry_type: entry.entryType,
            delta_minor: entry.deltaMinor,
            actor_fingerprint: entry.actorFingerprint,
            reason: entry.reason,
            external_reference: entry.externalReference,
            occurred_at: entry.occurredAt,
            created_at: entry.createdAt,
          })),
        ),
      ],
    );
    inserted += result.rowCount ?? 0;
  }
  return inserted;
}

async function targetRows(
  client: Client,
  editionIds: string[],
  expectedFineIds: string[],
): Promise<{
  copies: TargetCopy[];
  loans: TargetLoan[];
  fines: TargetFine[];
  fineLedgerEntries: TargetFineLedgerEntry[];
}> {
  if (editionIds.length === 0) {
    return { copies: [], loans: [], fines: [], fineLedgerEntries: [] };
  }
  const copies = await client.query<{
    id: string;
    edition_id: string;
    branch_id: string;
    barcode: string;
    status: TargetCopy["status"];
    shelf_location: string | null;
    version: string;
    created_at: Date;
    updated_at: Date;
  }>(
    `
      SELECT
        id::text,
        edition_id::text,
        branch_id::text,
        barcode,
        status,
        shelf_location,
        version::text,
        created_at,
        updated_at
      FROM public.circulation_copy
      WHERE edition_id = ANY($1::uuid[])
      ORDER BY id
    `,
    [editionIds],
  );
  const loans = await client.query<{
    id: string;
    member_id: string;
    edition_id: string;
    copy_id: string | null;
    status: TargetLoan["status"];
    requested_at: Date;
    checked_out_at: Date | null;
    due_at: Date | null;
    returned_at: Date | null;
    rejected_at: Date | null;
    version: string;
    renewal_count: number;
    created_at: Date;
    updated_at: Date;
  }>(
    `
      SELECT
        id::text,
        member_id::text,
        edition_id::text,
        copy_id::text,
        status,
        requested_at,
        checked_out_at,
        due_at,
        returned_at,
        rejected_at,
        version::text,
        renewal_count,
        created_at,
        updated_at
      FROM public.circulation_loan
      WHERE edition_id = ANY($1::uuid[])
      ORDER BY id
    `,
    [editionIds],
  );
  const loanIds = loans.rows.map((row) => row.id);
  const fines = await client.query<{
    id: string;
    loan_id: string;
    member_id: string;
    currency: string;
    balance_minor: string;
    status: string;
    version: string;
    created_at: Date;
    updated_at: Date;
  }>(
    `
      SELECT
        id::text,
        loan_id::text,
        member_id::text,
        currency,
        balance_minor::text,
        status,
        version::text,
        created_at,
        updated_at
      FROM public.circulation_fine
      WHERE loan_id = ANY($1::uuid[])
      ORDER BY id
    `,
    [loanIds],
  );
  const fineIds = [
    ...new Set([...fines.rows.map((row) => row.id), ...expectedFineIds]),
  ];
  const fineLedgerEntries =
    fineIds.length === 0
      ? { rows: [] as Array<{
          id: string;
          fine_id: string;
          fine_version: string;
          entry_type: string;
          delta_minor: string;
          actor_fingerprint: string;
          reason: string | null;
          external_reference: string | null;
          occurred_at: Date;
          created_at: Date;
        }> }
      : await client.query<{
          id: string;
          fine_id: string;
          fine_version: string;
          entry_type: string;
          delta_minor: string;
          actor_fingerprint: string;
          reason: string | null;
          external_reference: string | null;
          occurred_at: Date;
          created_at: Date;
        }>(
          `
            SELECT
              id::text,
              fine_id::text,
              fine_version::text,
              entry_type,
              delta_minor::text,
              actor_fingerprint,
              reason,
              external_reference,
              occurred_at,
              created_at
            FROM public.circulation_fine_ledger_entry
            WHERE fine_id = ANY($1::uuid[])
            ORDER BY id
          `,
          [fineIds],
        );
  return {
    copies: copies.rows.map((row) => ({
      id: row.id,
      editionId: row.edition_id,
      branchId: row.branch_id,
      barcode: row.barcode,
      status: row.status,
      shelfLocation: row.shelf_location,
      version: asSafeInteger(row.version, "copy.version"),
      createdAt: row.created_at.toISOString(),
      updatedAt: row.updated_at.toISOString(),
    })),
    loans: loans.rows.map((row) => ({
      id: row.id,
      memberId: row.member_id,
      editionId: row.edition_id,
      copyId: row.copy_id,
      status: row.status,
      requestedAt: row.requested_at.toISOString(),
      checkedOutAt: asIso(row.checked_out_at),
      dueAt: asIso(row.due_at),
      returnedAt: asIso(row.returned_at),
      rejectedAt: asIso(row.rejected_at),
      version: asSafeInteger(row.version, "loan.version"),
      renewalCount: asSafeInteger(
        row.renewal_count,
        "loan.renewal_count",
      ),
      createdAt: row.created_at.toISOString(),
      updatedAt: row.updated_at.toISOString(),
    })),
    fines: fines.rows.map((row) => ({
      id: row.id,
      loanId: row.loan_id,
      memberId: row.member_id,
      currency: row.currency,
      balanceMinor: asSafeInteger(row.balance_minor, "fine.balance_minor"),
      status: row.status,
      version: asSafeInteger(row.version, "fine.version"),
      createdAt: row.created_at.toISOString(),
      updatedAt: row.updated_at.toISOString(),
    })),
    fineLedgerEntries: fineLedgerEntries.rows.map((row) => ({
      id: row.id,
      fineId: row.fine_id,
      fineVersion: asSafeInteger(
        row.fine_version,
        "fine_ledger_entry.fine_version",
      ),
      entryType: row.entry_type,
      deltaMinor: asSafeInteger(
        row.delta_minor,
        "fine_ledger_entry.delta_minor",
      ),
      actorFingerprint: row.actor_fingerprint,
      reason: row.reason,
      externalReference: row.external_reference,
      occurredAt: row.occurred_at.toISOString(),
      createdAt: row.created_at.toISOString(),
    })),
  };
}

function editions(plan: MigrationPlan): string[] {
  return [
    ...new Set([
      ...plan.target.copies.map((copy) => copy.editionId),
      ...plan.target.loans.map((loan) => loan.editionId),
      ...plan.reconciliation.editions.map((edition) => edition.editionId),
    ]),
  ].sort();
}

export async function reconcileTarget(options: {
  plan: MigrationPlan;
  targetUrl: string;
  expectedDatabase: string;
}): Promise<ReconciliationReport> {
  const plan = verifyPlan(options.plan);
  const client = await connect(
    options.targetUrl,
    options.expectedDatabase,
    "mundia_circulation_reconcile",
  );
  try {
    await client.query(
      "BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE",
    );
    await client.query("SET LOCAL statement_timeout = '120s'");
    await client.query("SET LOCAL lock_timeout = '5s'");
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '5min'");
    const actualIdentity = await identity(client);
    assertExpectedIdentity(actualIdentity, options.expectedDatabase);
    if (actualIdentity.serverVersionNumber < 180_000) {
      throw new Error(
        `Target PostgreSQL ${actualIdentity.serverVersion} is below the PostgreSQL 18 production baseline`,
      );
    }
    await lockTargetSchema(client);
    await verifyTargetSchema(client);
    const rows = await targetRows(
      client,
      editions(plan),
      plan.target.fines.map((fine) => fine.id),
    );
    const observedAt = new Date().toISOString();
    await client.query("COMMIT");
    return reconcile({
      plan,
      actualCopies: rows.copies,
      actualLoans: rows.loans,
      actualFines: rows.fines,
      actualFineLedgerEntries: rows.fineLedgerEntries,
      observedAt,
      database: actualIdentity.database,
      serverVersion: actualIdentity.serverVersion,
    });
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    await client.end();
  }
}

export async function applyPlan(options: {
  plan: MigrationPlan;
  targetUrl: string;
  expectedDatabase: string;
  writeAcknowledgement: string | undefined;
}): Promise<ReconciliationReport> {
  const plan = verifyPlan(options.plan);
  if (options.writeAcknowledgement !== WRITE_ACK) {
    throw new Error(`CIRCULATION_MIGRATION_WRITE_ACK must equal ${WRITE_ACK}`);
  }
  if (plan.reconciliation.status !== "READY") {
    throw new Error("Refusing to apply a BLOCKED migration plan");
  }
  if (plan.target.copies.length === 0 && plan.target.loans.length === 0) {
    throw new Error("Refusing to apply an empty migration plan");
  }

  const client = await connect(
    options.targetUrl,
    options.expectedDatabase,
    "mundia_circulation_apply",
  );
  let advisoryLockHeld = false;
  try {
    // Acquire the session lock before BEGIN. A SERIALIZABLE transaction takes
    // its snapshot at the first statement, so waiting on an xact advisory lock
    // inside the transaction can leave a concurrent replay with a stale
    // snapshot and a 40001 serialization failure.
    await client.query("SET statement_timeout = '120s'");
    await client.query("SET lock_timeout = '10s'");
    await client.query("SELECT pg_advisory_lock(612458993, 20260726)");
    advisoryLockHeld = true;
    await client.query("BEGIN ISOLATION LEVEL SERIALIZABLE");
    await client.query("SET LOCAL statement_timeout = '120s'");
    await client.query("SET LOCAL lock_timeout = '10s'");
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '10min'");
    const actualIdentity = await identity(client);
    assertExpectedIdentity(actualIdentity, options.expectedDatabase);
    if (actualIdentity.serverVersionNumber < 180_000) {
      throw new Error(
        `Target PostgreSQL ${actualIdentity.serverVersion} is below the PostgreSQL 18 production baseline`,
      );
    }
    await lockTargetSchema(client);
    await verifyTargetSchema(client);
    const insertedCopies = await insertCopies(client, plan.target.copies);
    const insertedLoans = await insertLoans(client, plan.target.loans);
    const insertedFines = await insertFines(client, plan.target.fines);
    const insertedFineLedgerEntries = await insertFineLedgerEntries(
      client,
      plan.target.fineLedgerEntries,
    );
    const rows = await targetRows(
      client,
      editions(plan),
      plan.target.fines.map((fine) => fine.id),
    );
    const observedAt = new Date().toISOString();
    const comparison = reconcile({
      plan,
      actualCopies: rows.copies,
      actualLoans: rows.loans,
      actualFines: rows.fines,
      actualFineLedgerEntries: rows.fineLedgerEntries,
      observedAt,
      database: actualIdentity.database,
      serverVersion: actualIdentity.serverVersion,
    });
    if (comparison.status !== "MATCH") {
      await client.query("ROLLBACK");
      return reconcile({
        plan,
        actualCopies: rows.copies,
        actualLoans: rows.loans,
        actualFines: rows.fines,
        actualFineLedgerEntries: rows.fineLedgerEntries,
        observedAt,
        database: actualIdentity.database,
        serverVersion: actualIdentity.serverVersion,
        application: {
          transactionOutcome: "ROLLED_BACK",
          transactionFinishedAt: new Date().toISOString(),
          insertedCopies,
          insertedLoans,
          insertedFines,
          insertedFineLedgerEntries,
        },
      });
    }
    await client.query("COMMIT");
    return reconcile({
      plan,
      actualCopies: rows.copies,
      actualLoans: rows.loans,
      actualFines: rows.fines,
      actualFineLedgerEntries: rows.fineLedgerEntries,
      observedAt,
      database: actualIdentity.database,
      serverVersion: actualIdentity.serverVersion,
      application: {
        transactionOutcome: "COMMITTED",
        transactionFinishedAt: new Date().toISOString(),
        insertedCopies,
        insertedLoans,
        insertedFines,
        insertedFineLedgerEntries,
      },
    });
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    if (advisoryLockHeld) {
      await client
        .query("SELECT pg_advisory_unlock(612458993, 20260726)")
        .catch(() => undefined);
    }
    await client.end();
  }
}
