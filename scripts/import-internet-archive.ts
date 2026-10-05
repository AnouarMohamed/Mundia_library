import dotenv from "dotenv";
import { fetchInternetArchiveBatch } from "../lib/learning-resources/internet-archive";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Internet Archive · NASA Technical Reports";
const MAX_BATCH_SIZE = 50;

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const batch = await fetchInternetArchiveBatch({ limit: args.limit });
  const summary = {
    source: SOURCE_NAME,
    revision: batch.revision,
    matches: batch.matches,
    selected: batch.candidates.length,
    verified: batch.candidates.filter(
      (item) => item.verificationStatus === "VERIFIED",
    ).length,
    quarantined: batch.candidates.filter(
      (item) => item.verificationStatus === "QUARANTINED",
    ).length,
    categories: Object.fromEntries(
      [...new Set(batch.candidates.map((item) => item.category))]
        .sort()
        .map((category) => [
          category,
          batch.candidates.filter((item) => item.category === category).length,
        ]),
    ),
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) {
    throw new Error("DATABASE_URL is required for --apply");
  }
  if (args.expectedRevision !== batch.revision) {
    throw new Error(
      "Internet Archive batch changed; dry-run again and use --expected-revision",
    );
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: batch.revision,
      offset: 700_000,
      limit: MAX_BATCH_SIZE,
      candidates: batch.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let limit = MAX_BATCH_SIZE;
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
    if (value === "--limit") limit = Number(next);
    else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_BATCH_SIZE) {
    throw new Error("Use --limit between 1 and 50");
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/u.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision) {
    throw new Error("--apply requires --expected-revision");
  }
  return { limit, apply, expectedRevision };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
