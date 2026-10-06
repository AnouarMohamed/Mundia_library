import dotenv from "dotenv";
import { asc, eq } from "drizzle-orm";
import { learningResources } from "../database/schema";
import {
  applyCatalogBackfillBatch,
  createCatalogBackfillBatches,
} from "../lib/learning-resources/kotlin-catalog-backfill";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

async function main() {
  const { apply, offset, limit, batchSize } = parseArguments(process.argv.slice(2));
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required");
  const { db, closeDb } = await import("../database/drizzle");
  try {
    const records = await db
      .select({
        id: learningResources.id,
        sourceName: learningResources.sourceName,
        sourceRecordKey: learningResources.sourceRecordKey,
        title: learningResources.title,
        author: learningResources.author,
        description: learningResources.description,
        coverUrl: learningResources.coverUrl,
        coverAlt: learningResources.coverAlt,
        category: learningResources.category,
        language: learningResources.language,
        sourceUrl: learningResources.sourceUrl,
        licenseExpression: learningResources.licenseExpression,
        licenseUrl: learningResources.licenseUrl,
        downloadUrl: learningResources.downloadUrl,
        readUrl: learningResources.readUrl,
        contentHash: learningResources.contentHash,
        sourceRevision: learningResources.sourceRevision,
      })
      .from(learningResources)
      .where(eq(learningResources.verificationStatus, "VERIFIED"))
      .orderBy(
        asc(learningResources.sourceName),
        asc(learningResources.sourceRevision),
        asc(learningResources.sourceRecordKey),
      )
      .limit(limit)
      .offset(offset);
    const batches = createCatalogBackfillBatches(records, batchSize);
    if (!apply) {
      console.log(JSON.stringify({ mode: "dry-run", records: records.length, batches: batches.length }));
      return;
    }
    const baseUrl = process.env.CATALOG_SERVICE_URL;
    const bearerToken = process.env.CATALOG_IMPORT_BEARER_TOKEN;
    if (!baseUrl || !bearerToken) {
      throw new Error("CATALOG_SERVICE_URL and CATALOG_IMPORT_BEARER_TOKEN are required for --apply");
    }
    let reconciled = 0;
    for (const batch of batches) {
      await applyCatalogBackfillBatch({ baseUrl, bearerToken, batch });
      reconciled += batch.items.length;
    }
    console.log(JSON.stringify({ mode: "applied", records: records.length, batches: batches.length, reconciled }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let apply = false;
  let offset = 0;
  let limit = 250;
  let batchSize = 250;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") apply = true;
    else if (value === "--offset") offset = Number(values[++index]);
    else if (value === "--limit") limit = Number(values[++index]);
    else if (value === "--batch-size") batchSize = Number(values[++index]);
    else throw new Error(`Unknown argument: ${value}`);
  }
  if (
    !Number.isSafeInteger(offset) || offset < 0 ||
    !Number.isSafeInteger(limit) || limit < 1 || limit > 10_000 ||
    !Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > 250
  ) {
    throw new Error("Use offset >= 0, limit 1-10000, and batch-size 1-250");
  }
  return { apply, offset, limit, batchSize };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Catalog backfill failed");
  process.exitCode = 1;
});
