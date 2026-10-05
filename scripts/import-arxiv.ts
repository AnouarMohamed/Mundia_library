import dotenv from "dotenv";
import { fetchArxivPage } from "../lib/learning-resources/arxiv";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "arXiv";
const MAX_BATCH_SIZE = 250;

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const page = await fetchArxivPage({
    set: args.set,
    from: args.from,
    limit: args.limit,
  });
  const summary = {
    source: SOURCE_NAME,
    revision: page.revision,
    set: args.set,
    from: args.from,
    sourceRecords: page.sourceRecords,
    selected: page.candidates.length,
    verified: page.candidates.filter(
      (item) => item.verificationStatus === "VERIFIED",
    ).length,
    quarantined: page.candidates.filter(
      (item) => item.verificationStatus === "QUARANTINED",
    ).length,
    categories: Object.fromEntries(
      [...new Set(page.candidates.map((item) => item.category))]
        .sort()
        .map((category) => [
          category,
          page.candidates.filter((item) => item.category === category).length,
        ]),
    ),
    hasMore: Boolean(page.nextToken),
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) {
    throw new Error("DATABASE_URL is required for --apply");
  }
  if (args.expectedRevision !== page.revision) {
    throw new Error(
      "arXiv page changed; dry-run again and use --expected-revision",
    );
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: page.revision,
      offset: 600_000,
      limit: MAX_BATCH_SIZE,
      candidates: page.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let set = "cs:cs:CR";
  let from = defaultFromDate();
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
    if (value === "--set") set = next;
    else if (value === "--from") from = next;
    else if (value === "--limit") limit = Number(next);
    else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }

  if (!/^\d{4}-\d{2}-\d{2}$/u.test(from)) {
    throw new Error("Use YYYY-MM-DD for --from");
  }
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_BATCH_SIZE) {
    throw new Error("Use --limit between 1 and 250");
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/u.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision) {
    throw new Error("--apply requires --expected-revision");
  }
  return { set, from, limit, apply, expectedRevision };
}

function defaultFromDate() {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - 7);
  return date.toISOString().slice(0, 10);
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
