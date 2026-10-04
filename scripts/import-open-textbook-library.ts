import dotenv from "dotenv";
import {
  fetchOpenTextbookPage,
  OTL_SUBJECTS,
} from "../lib/learning-resources/open-textbook-library";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Open Textbook Library";

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const result = await fetchOpenTextbookPage({
    subjectId: args.subjectId,
    page: args.page,
  });
  const summary = {
    source: SOURCE_NAME,
    subject: OTL_SUBJECTS[args.subjectId as keyof typeof OTL_SUBJECTS],
    subjectId: args.subjectId,
    page: args.page,
    totalPages: result.totalPages,
    totalCount: result.totalCount,
    revision: result.revision,
    selected: result.candidates.length,
    verified: result.candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length,
    directDownloads: result.candidates.filter(
      (candidate) => Boolean(candidate.downloadUrl),
    ).length,
    quarantined: result.candidates.filter(
      (candidate) => candidate.verificationStatus === "QUARANTINED",
    ).length,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) throw new Error("DATABASE_URL is required for --apply");
  if (args.expectedRevision !== result.revision) {
    throw new Error("OTL page changed; dry-run again and use --expected-revision");
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const imported = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: result.revision,
      offset: args.subjectId * 1_000 + args.page,
      limit: 250,
      candidates: result.candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result: imported }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]) {
  let subjectId = 3;
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
    if (value === "--subject") subjectId = Number(next);
    else if (value === "--page") page = Number(next);
    else if (value === "--expected-revision") expectedRevision = next;
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (!Object.hasOwn(OTL_SUBJECTS, subjectId)) throw new Error("Use subject 3, 7, or 13");
  if (!Number.isSafeInteger(page) || page < 1 || page > 1_000) throw new Error("Use page 1-1000");
  if (expectedRevision && !/^[0-9a-f]{64}$/.test(expectedRevision)) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  if (apply && !expectedRevision) throw new Error("--apply requires --expected-revision");
  return { subjectId, page, expectedRevision, apply };
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : "Import failed");
  process.exitCode = 1;
});
