import dotenv from "dotenv";
import {
  fetchNistPublications,
  NIST_PUBLICATIONS,
  NIST_SOURCE_NAME,
} from "../lib/learning-resources/nist";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const batch = await fetchNistPublications();
  const summary = {
    source: NIST_SOURCE_NAME,
    revision: batch.revision,
    selected: batch.candidates.length,
    verified: batch.candidates.filter(
      (item) => item.verificationStatus === "VERIFIED",
    ).length,
    quarantined: batch.candidates.filter(
      (item) => item.verificationStatus === "QUARANTINED",
    ).length,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL)
    throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== batch.revision) {
    throw new Error(
      "NIST publications changed; dry-run again and use --expected-revision",
    );
  }
  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: NIST_SOURCE_NAME,
      sourceRevision: batch.revision,
      offset: 0,
      limit: NIST_PUBLICATIONS.length,
      candidates: batch.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let apply = false;
  let expectedRevision: string | undefined;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      apply = true;
      continue;
    }
    if (value !== "--expected-revision")
      throw new Error(`Unknown argument: ${value}`);
    expectedRevision = values[index + 1];
    if (!expectedRevision)
      throw new Error("Missing value for --expected-revision");
    index += 1;
  }
  if (expectedRevision && !/^[0-9a-f]{64}$/.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision)
    throw new Error("--apply requires --expected-revision");
  return { apply, expectedRevision };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
