import { createHash } from "crypto";
import dotenv from "dotenv";
import { fetchDoabPage } from "../lib/learning-resources/doab";
import type { LearningResourceCandidate } from "../lib/learning-resources/types";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Directory of Open Access Books";
const MAX_BATCH_SIZE = 250;

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const pages: Array<{ revision: string; sourceRecords: number }> = [];
  const byKey = new Map<string, LearningResourceCandidate>();
  let token: string | undefined;
  let nextToken: string | null = null;

  for (let pageNumber = 0; pageNumber < args.maxPages; pageNumber += 1) {
    const page = await fetchDoabPage({ resumptionToken: token });
    pages.push({ revision: page.revision, sourceRecords: page.sourceRecords });
    for (const candidate of page.candidates) {
      byKey.set(candidate.sourceRecordKey, candidate);
    }
    if (byKey.size > MAX_BATCH_SIZE) {
      throw new Error(
        "DOAB_COLLECTION_EXCEEDS_BATCH_LIMIT; reduce --max-pages",
      );
    }
    nextToken = page.nextToken;
    if (!nextToken) break;
    token = nextToken;
  }

  const revision = sha256(JSON.stringify({ pages, nextToken }));
  const candidates = [...byKey.values()].map((candidate) => {
    const revised = { ...candidate, sourceRevision: revision, contentHash: "" };
    return { ...revised, contentHash: sha256(JSON.stringify(revised)) };
  });
  const summary = {
    source: SOURCE_NAME,
    revision,
    pages: pages.length,
    sourceRecords: pages.reduce((total, page) => total + page.sourceRecords, 0),
    selected: candidates.length,
    verified: candidates.filter(
      (item) => item.verificationStatus === "VERIFIED",
    ).length,
    withCovers: candidates.filter((item) => Boolean(item.coverUrl)).length,
    quarantined: candidates.filter(
      (item) => item.verificationStatus === "QUARANTINED",
    ).length,
    categories: Object.fromEntries(
      [...new Set(candidates.map((item) => item.category))]
        .sort()
        .map((category) => [
          category,
          candidates.filter((item) => item.category === category).length,
        ]),
    ),
    hasMore: Boolean(nextToken),
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL)
    throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== revision) {
    throw new Error(
      "DOAB collection changed; dry-run again and use --expected-revision",
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
      offset: 500_000,
      limit: MAX_BATCH_SIZE,
      candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let maxPages = 8;
  let apply = false;
  let expectedRevision: string | undefined;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      apply = true;
      continue;
    }
    const next = values[index + 1];
    if (!next) throw new Error(`Missing value for ${value}`);
    if (value === "--max-pages") maxPages = Number(next);
    else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (!Number.isSafeInteger(maxPages) || maxPages < 1 || maxPages > 10) {
    throw new Error("Use --max-pages between 1 and 10");
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/u.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision)
    throw new Error("--apply requires --expected-revision");
  return { maxPages, apply, expectedRevision };
}

function sha256(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
