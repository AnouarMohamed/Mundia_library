import { constants } from "node:fs";
import { open, realpath, stat } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import dotenv from "dotenv";
import { asc, sql } from "drizzle-orm";
import { bookReviews, books } from "../database/schema";
import {
  applyLegacyCatalogBatch,
  planLegacyCatalogBackfill,
  type LegacyCatalogRecord,
} from "../lib/catalog-migration/legacy-catalog-backfill";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

async function main() {
  const { apply, batchSize, evidenceFile, sourceUrlFile, expectedDatabase } = parseArguments(process.argv.slice(2));
  const sourceUrl = await readPrivateSourceUrl(sourceUrlFile, expectedDatabase);
  process.env.DATABASE_URL = sourceUrl;
  const { db, closeDb } = await import("../database/drizzle");
  try {
    const [sourceBooks, sourceReviews] = await db.transaction(async (tx) => {
      await tx.execute(sql.raw("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE"));
      await tx.execute(sql.raw("SET LOCAL statement_timeout = '120s'"));
      await tx.execute(sql.raw("SET LOCAL lock_timeout = '5s'"));
      await tx.execute(sql.raw("LOCK TABLE public.books, public.book_reviews IN ACCESS SHARE MODE"));
      const identity = await tx.execute(sql<{ database: string; version: string }>`
        SELECT current_database() AS database, current_setting('server_version_num') AS version
      `);
      const database = identity.rows[0];
      if (!database || database.database !== expectedDatabase || Number(database.version) < 180_000) {
        throw new Error("Source identity does not match the reviewed PostgreSQL 18 restore");
      }
      return [
        await tx.select().from(books).orderBy(asc(books.id)),
        await tx.select().from(bookReviews).orderBy(asc(bookReviews.bookId), asc(bookReviews.id)),
      ] as const;
    });
    if (sourceBooks.length > 10_000) throw new Error("Legacy catalog exceeds the 10000-record migration envelope");
    if (sourceReviews.length > 100_000) throw new Error("Legacy reviews exceed the 100000-record migration envelope");
    const reviewsByBook = Map.groupBy(sourceReviews, (review) => review.bookId);
    const records: LegacyCatalogRecord[] = sourceBooks.map((book) => ({
      id: book.id,
      title: book.title,
      author: book.author,
      genre: book.genre,
      rating: book.rating,
      ratingCount: reviewsByBook.get(book.id)?.length ?? 0,
      summary: book.summary,
      description: book.description,
      isbn: book.isbn,
      publisher: book.publisher,
      publicationYear: book.publicationYear,
      language: book.language,
      pageCount: book.pageCount,
      coverUrl: book.coverUrl,
      coverColor: book.coverColor,
      videoUrl: book.videoUrl,
      isActive: book.isActive,
      createdAt: book.createdAt,
      updatedAt: book.updatedAt,
      reviews: (reviewsByBook.get(book.id) ?? []).map((review) => ({
        id: review.id,
        userId: review.userId,
        rating: review.rating,
        comment: review.comment,
        createdAt: review.createdAt,
        updatedAt: review.updatedAt,
      })),
    }));
    const plan = planLegacyCatalogBackfill(records, batchSize);
    if (!apply) {
      await emitEvidence(evidenceFile, {
        mode: "dry-run",
        status: plan.status,
        sourceRevision: plan.sourceRevision,
        sourceCount: plan.sourceCount,
        batches: plan.batches.map((batch) => ({
          importId: batch.importId,
          workCount: batch.items.length,
          reviewCount: batch.items.reduce((total, item) => total + item.reviews.length, 0),
        })),
        findings: plan.findings,
      });
      return;
    }
    if (plan.status !== "READY") throw new Error("Catalog backfill is blocked by incomplete legacy metadata");
    const baseUrl = process.env.CATALOG_SERVICE_URL;
    const bearerToken = process.env.CATALOG_IMPORT_BEARER_TOKEN;
    if (!baseUrl || !bearerToken) {
      throw new Error("CATALOG_SERVICE_URL and CATALOG_IMPORT_BEARER_TOKEN are required for --apply");
    }
    const receipts = [];
    for (const batch of plan.batches) {
      receipts.push(await applyLegacyCatalogBatch({ baseUrl, bearerToken, batch }));
    }
    await emitEvidence(evidenceFile, {
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

function parseArguments(values: string[]) {
  let apply = false;
  let batchSize = 10;
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
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > 10) {
    throw new Error("Use batch-size 1-10");
  }
  if (!evidenceFile) throw new Error("--evidence-file is required to protect migration evidence");
  if (!sourceUrlFile || !expectedDatabase) {
    throw new Error("--source-url-file and --expect-database are required");
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
    if (!["postgres:", "postgresql:"].includes(url.protocol) || !["127.0.0.1", "[::1]"].includes(url.hostname)) {
      throw new Error("Source must be an isolated PostgreSQL restore on literal loopback");
    }
    if (url.search || url.hash || !url.username) throw new Error("Source URL must name a role and contain no query or fragment");
    const database = decodeURIComponent(url.pathname).replace(/^\//, "");
    if (!database || database !== expectedDatabase) throw new Error("Source URL database does not match --expect-database");
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
  process.stdout.write(JSON.stringify({ status: "EVIDENCE_WRITTEN", path: resolved }) + "\n");
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Catalog backfill failed");
  process.exitCode = 1;
});
