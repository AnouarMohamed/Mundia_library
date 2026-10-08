import { constants } from "node:fs";
import { open, realpath, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import dotenv from "dotenv";
import { asc, sql } from "drizzle-orm";
import { borrowRecords, users } from "../database/schema";
import {
  applyLegacyMembershipBatch,
  planLegacyMembershipBackfill,
  type LegacyMembershipRecord,
} from "../lib/membership-migration/legacy-membership-backfill";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

async function main() {
  const options = parseArguments(process.argv.slice(2));
  process.env.DATABASE_URL = await readPrivateSourceUrl(options.sourceUrlFile, options.expectedDatabase);
  const { db, closeDb } = await import("../database/drizzle");
  try {
    const [sourceUsers, sourceLoans] = await db.transaction(async (tx) => {
      await tx.execute(sql.raw("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE"));
      await tx.execute(sql.raw("SET LOCAL statement_timeout = '120s'"));
      await tx.execute(sql.raw("SET LOCAL lock_timeout = '5s'"));
      await tx.execute(sql.raw("LOCK TABLE public.users, public.borrow_records IN ACCESS SHARE MODE"));
      const identity = await tx.execute(sql<{ database: string; version: string }>`
        SELECT current_database() AS database, current_setting('server_version_num') AS version
      `);
      const database = identity.rows[0];
      if (!database || database.database !== options.expectedDatabase || Number(database.version) < 180_000) {
        throw new Error("Source identity does not match the reviewed PostgreSQL 18 restore");
      }
      const counts = await tx.execute(sql<{ members: string; loans: string }>`
        SELECT
          (SELECT count(*) FROM public.users)::text AS members,
          (SELECT count(*) FROM public.borrow_records)::text AS loans
      `);
      const sourceCounts = counts.rows[0];
      const memberCount = Number(sourceCounts?.members);
      const loanCount = Number(sourceCounts?.loans);
      if (!Number.isSafeInteger(memberCount) || memberCount < 0 || memberCount > 10_000) {
        throw new Error("Legacy membership exceeds the 10000-record envelope");
      }
      if (!Number.isSafeInteger(loanCount) || loanCount < 0 || loanCount > 100_000) {
        throw new Error("Legacy loans exceed the 100000-record envelope");
      }
      return [
        await tx
          .select({
            id: users.id,
            email: users.email,
            fullName: users.fullName,
            universityId: users.universityId,
            status: users.status,
            role: users.role,
            universityCard: users.universityCard,
            createdAt: users.createdAt,
          })
          .from(users)
          .orderBy(asc(users.id)),
        await tx
          .select({
            id: borrowRecords.id,
            userId: borrowRecords.userId,
            borrowDate: borrowRecords.borrowDate,
            status: borrowRecords.status,
            fineAmount: borrowRecords.fineAmount,
            updatedAt: borrowRecords.updatedAt,
            createdAt: borrowRecords.createdAt,
          })
          .from(borrowRecords)
          .orderBy(asc(borrowRecords.userId), asc(borrowRecords.id)),
      ] as const;
    });
    const loansByMember = Map.groupBy(sourceLoans, (loan) => loan.userId);
    const records: LegacyMembershipRecord[] = sourceUsers.map((member) => {
      const loans = loansByMember.get(member.id) ?? [];
      const activeLoans = loans.filter((loan) => loan.status === "BORROWED").length;
      const createdAt = member.createdAt;
      const updatedAt = latestDate([
        createdAt,
        ...loans.flatMap((loan) => [loan.updatedAt, loan.createdAt, loan.borrowDate]),
      ]);
      return {
        id: member.id,
        email: member.email,
        fullName: member.fullName,
        universityId: member.universityId,
        status: member.status,
        role: member.role,
        universityCard: member.universityCard,
        maxActiveLoans: Math.max(5, activeLoans),
        currentActiveLoans: activeLoans,
        hasUnpaidOverdueFines: loans.some((loan) => Number(loan.fineAmount ?? "0") > 0),
        createdAt,
        updatedAt,
      };
    });
    const plan = planLegacyMembershipBackfill(records, options.batchSize);
    if (!options.apply) {
      await emitEvidence(options.evidenceFile, summary(plan, "dry-run"));
      return;
    }
    if (plan.status !== "READY") throw new Error("Membership backfill is blocked by source findings");
    const baseUrl = process.env.MEMBERSHIP_SERVICE_URL;
    const bearerToken = process.env.MEMBERSHIP_IMPORT_BEARER_TOKEN;
    if (!baseUrl || !bearerToken) {
      throw new Error("MEMBERSHIP_SERVICE_URL and MEMBERSHIP_IMPORT_BEARER_TOKEN are required for --apply");
    }
    const receipts = [];
    for (const batch of plan.batches) {
      receipts.push(await applyLegacyMembershipBatch({ baseUrl, bearerToken, batch }));
    }
    await emitEvidence(options.evidenceFile, {
      mode: "applied",
      status: "MATCH",
      sourceRevision: plan.sourceRevision,
      sourceCount: plan.sourceCount,
      batchCount: plan.batches.length,
      receipts,
    });
  } finally {
    await closeDb();
  }
}

function summary(plan: ReturnType<typeof planLegacyMembershipBackfill>, mode: string) {
  return {
    mode,
    status: plan.status,
    sourceRevision: plan.sourceRevision,
    sourceCount: plan.sourceCount,
    batches: plan.batches.map((batch) => ({ importId: batch.importId, memberCount: batch.items.length })),
    findings: plan.findings,
  };
}

function latestDate(values: Array<Date | null>): Date | null {
  const dates = values.filter((value): value is Date => value !== null);
  return dates.length === 0 ? null : new Date(Math.max(...dates.map((value) => value.valueOf())));
}

function parseArguments(values: string[]) {
  let apply = false;
  let batchSize = 100;
  let evidenceFile: string | null = null;
  let sourceUrlFile: string | null = null;
  let expectedDatabase: string | null = null;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") apply = true;
    else if (value === "--batch-size") batchSize = Number(values[++index]);
    else if (value === "--evidence-file") evidenceFile = values[++index] ?? null;
    else if (value === "--source-url-file") sourceUrlFile = values[++index] ?? null;
    else if (value === "--expect-database") expectedDatabase = values[++index] ?? null;
    else throw new Error(`Unknown argument: ${value}`);
  }
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > 100) throw new Error("Use batch-size 1-100");
  if (!sourceUrlFile || !expectedDatabase || !evidenceFile) {
    throw new Error("--source-url-file, --expect-database, and --evidence-file are required");
  }
  return { apply, batchSize, evidenceFile, sourceUrlFile, expectedDatabase };
}

async function readPrivateSourceUrl(path: string, expectedDatabase: string): Promise<string> {
  const resolved = resolve(path);
  const parent = dirname(resolved);
  if (await realpath(parent) !== parent) throw new Error("Source URL parent directory must not contain symlinks");
  const handle = await open(resolved, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const metadata = await handle.stat();
    if (!metadata.isFile() || metadata.nlink !== 1 || (metadata.mode & 0o077) !== 0) {
      throw new Error("Source URL file must be a private regular file with one link and mode 0600");
    }
    if (typeof process.getuid === "function" && metadata.uid !== process.getuid()) {
      throw new Error("Source URL file must be owned by the current operator");
    }
    if (metadata.size > 16_384) throw new Error("Source URL file is too large");
    const value = (await handle.readFile("utf8")).replace(/\r?\n$/, "");
    if (!value || value.includes("\r") || value.includes("\n") || value.includes("\u0000")) {
      throw new Error("Source URL file must contain exactly one URL");
    }
    const url = new URL(value);
    if (![
      "postgres:",
      "postgresql:",
    ].includes(url.protocol) || !["127.0.0.1", "[::1]"].includes(url.hostname)) {
      throw new Error("Source must be an isolated PostgreSQL restore on literal loopback");
    }
    if (url.search || url.hash || !url.username) throw new Error("Source URL must name a role and contain no query or fragment");
    if (decodeURIComponent(url.pathname).replace(/^\//, "") !== expectedDatabase) {
      throw new Error("Source URL database does not match --expect-database");
    }
    return value;
  } finally {
    await handle.close();
  }
}

async function emitEvidence(path: string, value: unknown) {
  const resolved = resolve(path);
  const parent = dirname(resolved);
  if (await realpath(parent) !== parent) throw new Error("Evidence parent directory must not contain symlinks");
  const parentMetadata = await stat(parent);
  if (!parentMetadata.isDirectory() || (parentMetadata.mode & 0o077) !== 0) {
    throw new Error("Evidence parent must be a private directory");
  }
  if (typeof process.getuid === "function" && parentMetadata.uid !== process.getuid()) {
    throw new Error("Evidence parent must be owned by the current operator");
  }
  const handle = await open(resolved, "wx", 0o600);
  try {
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
  } finally {
    await handle.close();
  }
  process.stdout.write(`${JSON.stringify({ status: "EVIDENCE_WRITTEN", path: resolved })}\n`);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Membership backfill failed");
  process.exitCode = 1;
});
