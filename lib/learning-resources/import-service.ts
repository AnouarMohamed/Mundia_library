import { createHash } from "crypto";
import { and, eq, inArray, sql } from "drizzle-orm";
import { db } from "@/database/drizzle";
import {
  learningResourceImportRuns,
  learningResources,
} from "@/database/schema";
import type { LearningResourceCandidate } from "./types";

const MAX_BATCH_SIZE = 250;
const IMPORTER_VERSION = "ebook-foundation-v1";

export interface LearningResourceImportResult {
  replayed: boolean;
  batchKey: string;
  seen: number;
  inserted: number;
  updated: number;
  unchanged: number;
  verified: number;
  quarantined: number;
}

/**
 * Atomically applies one bounded source batch. A source-scoped advisory lock
 * keeps overlapping operator runs deterministic without a distributed lock.
 */
export async function importLearningResourceBatch(input: {
  sourceName: string;
  sourceRevision: string;
  offset: number;
  limit: number;
  candidates: LearningResourceCandidate[];
}): Promise<LearningResourceImportResult> {
  validateBatch(input);
  const batchKey = createBatchKey(input);

  return db.transaction(async (tx) => {
    await tx.execute(
      sql`select pg_advisory_xact_lock(hashtext(${`learning-resource-import:${input.sourceName}`}))`,
    );

    const completed = await tx
      .select()
      .from(learningResourceImportRuns)
      .where(eq(learningResourceImportRuns.batchKey, batchKey))
      .limit(1);
    if (completed[0]?.status === "COMPLETED") {
      return resultFromRun(completed[0], true);
    }
    if (completed.length > 0) {
      throw new Error("LEARNING_RESOURCE_IMPORT_INCOMPLETE_BATCH");
    }

    const [run] = await tx
      .insert(learningResourceImportRuns)
      .values({
        batchKey,
        sourceName: input.sourceName,
        sourceRevision: input.sourceRevision,
        batchOffset: input.offset,
        batchLimit: input.limit,
        status: "RUNNING",
      })
      .returning({ id: learningResourceImportRuns.id });

    const keys = input.candidates.map((candidate) => candidate.sourceRecordKey);
    const existing = keys.length
      ? await tx
          .select({
            id: learningResources.id,
            sourceRecordKey: learningResources.sourceRecordKey,
            contentHash: learningResources.contentHash,
          })
          .from(learningResources)
          .where(
            and(
              eq(learningResources.sourceName, input.sourceName),
              inArray(learningResources.sourceRecordKey, keys),
            ),
          )
      : [];
    const existingByKey = new Map(
      existing.map((resource) => [resource.sourceRecordKey, resource]),
    );

    let inserted = 0;
    let updated = 0;
    let unchanged = 0;
    const now = new Date();

    for (const candidate of input.candidates) {
      const current = existingByKey.get(candidate.sourceRecordKey);
      if (current?.contentHash === candidate.contentHash) {
        unchanged += 1;
        continue;
      }

      const values = {
        title: candidate.title,
        author: candidate.author,
        description: candidate.description ?? null,
        coverUrl: candidate.coverUrl ?? null,
        coverAlt: candidate.coverAlt ?? null,
        category: candidate.category,
        language: candidate.language,
        licenseExpression: candidate.licenseExpression,
        licenseUrl: candidate.licenseUrl,
        sourceUrl: candidate.sourceUrl,
        downloadUrl: candidate.downloadUrl,
        readUrl: candidate.readUrl,
        verificationStatus: candidate.verificationStatus,
        verificationReason: candidate.verificationReason,
        verificationEvidenceUrl: candidate.verificationEvidenceUrl,
        contentHash: candidate.contentHash,
        sourceRevision: candidate.sourceRevision,
        verifiedAt: candidate.verificationStatus === "VERIFIED" ? now : null,
        verifiedBy: null,
        updatedAt: now,
      };

      if (current) {
        await tx
          .update(learningResources)
          .set(values)
          .where(eq(learningResources.id, current.id));
        updated += 1;
      } else {
        await tx.insert(learningResources).values({
          sourceName: candidate.sourceName,
          sourceRecordKey: candidate.sourceRecordKey,
          ...values,
        });
        inserted += 1;
      }
    }

    const verified = input.candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length;
    const quarantined = input.candidates.length - verified;
    const [finished] = await tx
      .update(learningResourceImportRuns)
      .set({
        status: "COMPLETED",
        seenCount: input.candidates.length,
        insertedCount: inserted,
        updatedCount: updated,
        unchangedCount: unchanged,
        verifiedCount: verified,
        quarantinedCount: quarantined,
        finishedAt: now,
      })
      .where(eq(learningResourceImportRuns.id, run.id))
      .returning();

    return resultFromRun(finished, false);
  });
}

export function createBatchKey(input: {
  sourceName: string;
  sourceRevision: string;
  offset: number;
  limit: number;
}) {
  return createHash("sha256")
    .update(
      [
        IMPORTER_VERSION,
        input.sourceName,
        input.sourceRevision,
        String(input.offset),
        String(input.limit),
      ].join("\u0000"),
    )
    .digest("hex");
}

function validateBatch(input: {
  sourceName: string;
  sourceRevision: string;
  offset: number;
  limit: number;
  candidates: LearningResourceCandidate[];
}) {
  if (
    !Number.isSafeInteger(input.offset) ||
    input.offset < 0 ||
    !Number.isSafeInteger(input.limit) ||
    input.limit < 1 ||
    input.limit > MAX_BATCH_SIZE ||
    input.candidates.length > input.limit ||
    !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(input.sourceRevision) ||
    input.sourceName.length < 2 ||
    input.sourceName.length > 64 ||
    input.candidates.some(
      (candidate) =>
        candidate.sourceName !== input.sourceName ||
        candidate.sourceRevision !== input.sourceRevision,
    )
  ) {
    throw new Error("LEARNING_RESOURCE_IMPORT_INVALID_BATCH");
  }
}

function resultFromRun(
  run: typeof learningResourceImportRuns.$inferSelect,
  replayed: boolean,
): LearningResourceImportResult {
  return {
    replayed,
    batchKey: run.batchKey,
    seen: run.seenCount,
    inserted: run.insertedCount,
    updated: run.updatedCount,
    unchanged: run.unchangedCount,
    verified: run.verifiedCount,
    quarantined: run.quarantinedCount,
  };
}
