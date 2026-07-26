import { Client, type ClientConfig, type PoolClient } from "pg";
import { reconcile } from "./reconcile.js";
import { verifyPlan } from "./planner.js";
import {
  SNAPSHOT_SCHEMA,
  type LegacySnapshot,
  type MigrationPlan,
  type ReconciliationReport,
  type TargetCopy,
  type TargetLoan,
} from "./types.js";

const LOCAL_HOSTS = new Set(["localhost", "127.0.0.1", "::1", "[::1]"]);
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
      "Refusing database URL: only localhost, 127.0.0.1, or ::1 is permitted",
    );
  }
  if (parsed.search !== "") {
    throw new TypeError(
      "Database URL query parameters are forbidden because they can override connection safety",
    );
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
    connectionString,
    application_name: applicationName,
    ssl: false,
    connectionTimeoutMillis: 5_000,
    query_timeout: 30_000,
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
      "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY DEFERRABLE",
    );
    await client.query("SET LOCAL statement_timeout = '30s'");
    await client.query("SET LOCAL lock_timeout = '5s'");
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '30s'");
    const actual = await identity(client);
    assertExpectedIdentity(actual, options.expectedDatabase);

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

async function verifyTargetSchema(client: Client): Promise<void> {
  const result = await client.query<{
    copy_table: string | null;
    loan_table: string | null;
  }>(`
    SELECT
      to_regclass('public.circulation_copy')::text AS copy_table,
      to_regclass('public.circulation_loan')::text AS loan_table
  `);
  const row = result.rows[0];
  if (!row?.copy_table || !row.loan_table) {
    throw new Error(
      "Target circulation schema is absent; apply the service Flyway migrations first",
    );
  }
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

async function targetRows(
  client: Client,
  editionIds: string[],
): Promise<{ copies: TargetCopy[]; loans: TargetLoan[] }> {
  if (editionIds.length === 0) return { copies: [], loans: [] };
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
        created_at,
        updated_at
      FROM public.circulation_loan
      WHERE edition_id = ANY($1::uuid[])
      ORDER BY id
    `,
    [editionIds],
  );
  return {
    copies: copies.rows.map((row) => ({
      id: row.id,
      editionId: row.edition_id,
      branchId: row.branch_id,
      barcode: row.barcode,
      status: row.status,
      shelfLocation: row.shelf_location as null,
      version: Number(row.version),
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
      rejectedAt: row.rejected_at as null,
      version: Number(row.version),
      createdAt: row.created_at.toISOString(),
      updatedAt: row.updated_at.toISOString(),
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
      "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY DEFERRABLE",
    );
    await client.query("SET LOCAL statement_timeout = '30s'");
    const actualIdentity = await identity(client);
    assertExpectedIdentity(actualIdentity, options.expectedDatabase);
    await verifyTargetSchema(client);
    const rows = await targetRows(client, editions(plan));
    const observedAt = new Date().toISOString();
    await client.query("COMMIT");
    return reconcile({
      plan,
      actualCopies: rows.copies,
      actualLoans: rows.loans,
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
  try {
    await client.query("BEGIN ISOLATION LEVEL SERIALIZABLE");
    await client.query("SET LOCAL statement_timeout = '60s'");
    await client.query("SET LOCAL lock_timeout = '10s'");
    await client.query("SET LOCAL idle_in_transaction_session_timeout = '60s'");
    await client.query("SELECT pg_advisory_xact_lock(612458993, 20260726)");
    const actualIdentity = await identity(client);
    assertExpectedIdentity(actualIdentity, options.expectedDatabase);
    if (actualIdentity.serverVersionNumber < 180_000) {
      throw new Error(
        `Target PostgreSQL ${actualIdentity.serverVersion} is below the PostgreSQL 18 production baseline`,
      );
    }
    await verifyTargetSchema(client);
    const insertedCopies = await insertCopies(client, plan.target.copies);
    const insertedLoans = await insertLoans(client, plan.target.loans);
    const rows = await targetRows(client, editions(plan));
    const report = reconcile({
      plan,
      actualCopies: rows.copies,
      actualLoans: rows.loans,
      observedAt: new Date().toISOString(),
      database: actualIdentity.database,
      serverVersion: actualIdentity.serverVersion,
      application: {
        transactionOutcome: "COMMITTED",
        insertedCopies,
        insertedLoans,
      },
    });
    if (report.status !== "MATCH") {
      await client.query("ROLLBACK");
      return reconcile({
        plan,
        actualCopies: rows.copies,
        actualLoans: rows.loans,
        observedAt: report.observedAt,
        database: actualIdentity.database,
        serverVersion: actualIdentity.serverVersion,
        application: {
          transactionOutcome: "ROLLED_BACK",
          insertedCopies,
          insertedLoans,
        },
      });
    }
    await client.query("COMMIT");
    return report;
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  } finally {
    await client.end();
  }
}
