import dotenv from "dotenv";
import {
  fetchGutenbergPage,
  GUTENBERG_COLLECTIONS,
  type GutenbergCollection,
} from "../lib/learning-resources/gutenberg";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Project Gutenberg";
const COLLECTIONS = Object.keys(GUTENBERG_COLLECTIONS) as GutenbergCollection[];

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const page = await fetchGutenbergPage({
    collection: args.collection,
    page: args.page,
  });
  const summary = {
    source: SOURCE_NAME,
    collection: args.collection,
    page: args.page,
    revision: page.revision,
    selected: page.candidates.length,
    verified: page.candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length,
    quarantined: page.candidates.filter(
      (candidate) => candidate.verificationStatus === "QUARANTINED",
    ).length,
    hasNextPage: page.hasNextPage,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== page.revision) {
    throw new Error("Gutenberg page changed; dry-run again and use --expected-revision");
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: page.revision,
      offset: COLLECTIONS.indexOf(args.collection) * 100_000 + args.page * 25,
      limit: 25,
      candidates: page.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let collection: GutenbergCollection = "engineering";
  let page = 1;
  let expectedRevision: string | undefined;
  let apply = false;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      apply = true;
      continue;
    }
    const next = values[index + 1];
    if (!next) throw new Error(`Missing value for ${value}`);
    if (value === "--collection") {
      if (!COLLECTIONS.includes(next as GutenbergCollection)) {
        throw new Error(`Use --collection ${COLLECTIONS.join(", ")}`);
      }
      collection = next as GutenbergCollection;
    } else if (value === "--page") page = Number(next);
    else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (!Number.isSafeInteger(page) || page < 1 || page > 100) {
    throw new Error("Use --page between 1 and 100");
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision) throw new Error("--apply requires --expected-revision");
  return { collection, page, expectedRevision, apply };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
