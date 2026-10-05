import { createHash } from "crypto";
import dotenv from "dotenv";
import {
  fetchOpenTextbookPage,
  OTL_SUBJECTS,
  type OtlPage,
} from "../lib/learning-resources/open-textbook-library";
import type { LearningResourceCandidate } from "../lib/learning-resources/types";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Open Textbook Library";
const CURATED_SUBJECT_IDS = [13, 39, 21, 3, 35, 82] as const;

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const pages: OtlPage[] = [];
  const pageRevisions: Array<{ subjectId: number; revision: string }> = [];
  for (const subjectId of args.subjectIds) {
    const first = await fetchOpenTextbookPage({ subjectId, page: 1 });
    pages.push(first);
    pageRevisions.push({ subjectId, revision: first.revision });
    for (let page = 2; page <= first.totalPages; page += 1) {
      const result = await fetchOpenTextbookPage({ subjectId, page });
      pages.push(result);
      pageRevisions.push({ subjectId, revision: result.revision });
    }
  }
  const revision = sha256(JSON.stringify(pageRevisions));
  const candidates = deduplicate(
    pages.flatMap((page) => page.candidates),
    revision,
  );
  if (candidates.length > 250)
    throw new Error("OTL_COLLECTION_EXCEEDS_BATCH_LIMIT");
  const summary = {
    source: SOURCE_NAME,
    subjects: args.subjectIds.map((subjectId) => OTL_SUBJECTS[subjectId]),
    subjectIds: args.subjectIds,
    pages: pages.length,
    revision,
    selected: candidates.length,
    verified: candidates.filter(
      (item) => item.verificationStatus === "VERIFIED",
    ).length,
    directDownloads: candidates.filter((item) => Boolean(item.downloadUrl))
      .length,
    quarantined: candidates.filter(
      (item) => item.verificationStatus === "QUARANTINED",
    ).length,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL)
    throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== revision) {
    throw new Error(
      "OTL collection changed; dry-run again and use --expected-revision",
    );
  }
  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: revision,
      offset:
        args.subjectIds.length === 1 ? args.subjectIds[0]! * 10_000 : 990_000,
      limit: 250,
      candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function deduplicate(
  candidates: LearningResourceCandidate[],
  revision: string,
) {
  const byKey = new Map<string, LearningResourceCandidate>();
  for (const candidate of candidates) {
    const revised = { ...candidate, sourceRevision: revision, contentHash: "" };
    byKey.set(candidate.sourceRecordKey, {
      ...revised,
      contentHash: sha256(JSON.stringify(revised)),
    });
  }
  return [...byKey.values()];
}

function parseArguments(values: string[]) {
  let subjectIds: Array<keyof typeof OTL_SUBJECTS> = [13];
  let expectedRevision: string | undefined;
  let apply = false;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      apply = true;
      continue;
    }
    if (value !== "--subject") {
      if (value !== "--expected-revision")
        throw new Error(`Unknown argument: ${value}`);
      expectedRevision = values[index + 1];
      if (!expectedRevision)
        throw new Error("Missing value for --expected-revision");
      index += 1;
      continue;
    }
    const next = values[index + 1];
    if (next === "all") {
      subjectIds = [...CURATED_SUBJECT_IDS];
      index += 1;
      continue;
    }
    const subject = Number(next);
    if (!Object.hasOwn(OTL_SUBJECTS, subject)) {
      throw new Error(`Use subject ${Object.keys(OTL_SUBJECTS).join(", ")}`);
    }
    subjectIds = [subject as keyof typeof OTL_SUBJECTS];
    index += 1;
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision)
    throw new Error("--apply requires --expected-revision");
  return { subjectIds, expectedRevision, apply };
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
