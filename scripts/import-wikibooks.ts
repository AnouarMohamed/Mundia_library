import dotenv from "dotenv";
import {
  fetchWikibooksCollection,
  WIKIBOOK_COLLECTIONS,
  type WikibookCollection,
} from "../lib/learning-resources/wikibooks";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Wikibooks";
const COLLECTION_NAMES = Object.keys(WIKIBOOK_COLLECTIONS) as WikibookCollection[];

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const batch = await fetchWikibooksCollection({ collection: args.collection });
  const summary = {
    source: SOURCE_NAME,
    collection: args.collection,
    revision: batch.revision,
    selected: batch.candidates.length,
    verified: batch.candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length,
    quarantined: batch.candidates.filter(
      (candidate) => candidate.verificationStatus === "QUARANTINED",
    ).length,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== batch.revision) {
    throw new Error("Wikibooks collection changed; dry-run again and use --expected-revision");
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: batch.revision,
      offset: COLLECTION_NAMES.indexOf(args.collection) * 1_000,
      limit: 100,
      candidates: batch.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let collection: WikibookCollection = "unix";
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
      if (!COLLECTION_NAMES.includes(next as WikibookCollection)) {
        throw new Error(`Use --collection ${COLLECTION_NAMES.join(", ")}`);
      }
      collection = next as WikibookCollection;
    } else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision) throw new Error("--apply requires --expected-revision");
  return { collection, expectedRevision, apply };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
