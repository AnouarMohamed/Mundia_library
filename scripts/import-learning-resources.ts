import dotenv from "dotenv";
import {
  fetchEbookFoundationSnapshot,
  parseEbookFoundationCatalog,
} from "../lib/learning-resources/ebook-foundation";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "EbookFoundation/free-programming-books";

interface Arguments {
  offset: number;
  limit: number;
  apply: boolean;
  revision: string;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const snapshot = await fetchEbookFoundationSnapshot({ revision: args.revision });
  const allCandidates = parseEbookFoundationCatalog(snapshot);
  const candidates = allCandidates.slice(args.offset, args.offset + args.limit);
  const summary = {
    source: SOURCE_NAME,
    revision: snapshot.revision,
    total: allCandidates.length,
    offset: args.offset,
    limit: args.limit,
    selected: candidates.length,
    verified: candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length,
    quarantined: candidates.filter(
      (candidate) => candidate.verificationStatus === "QUARANTINED",
    ).length,
  };

  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!/^[0-9a-f]{40}$/.test(args.revision)) {
    throw new Error("--apply requires --revision with an exact 40-character Git SHA");
  }
  if (!process.env.DATABASE_URL) {
    throw new Error("DATABASE_URL is required for --apply");
  }

  const { importLearningResourceBatch } = await import(
    "../lib/learning-resources/import-service"
  );
  const result = await importLearningResourceBatch({
    sourceName: SOURCE_NAME,
    sourceRevision: snapshot.revision,
    offset: args.offset,
    limit: args.limit,
    candidates,
  });
  console.log(JSON.stringify({ mode: "applied", ...summary, result }));
}

function parseArguments(values: string[]): Arguments {
  let offset = 0;
  let limit = 100;
  let apply = false;
  let revision = "main";
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      apply = true;
      continue;
    }
    if (value === "--offset") {
      offset = Number(values[index + 1]);
      index += 1;
      continue;
    }
    if (value === "--limit") {
      limit = Number(values[index + 1]);
      index += 1;
      continue;
    }
    if (value === "--revision") {
      revision = values[index + 1] ?? "";
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${value}`);
  }
  if (
    !Number.isSafeInteger(offset) ||
    offset < 0 ||
    !Number.isSafeInteger(limit) ||
    limit < 1 ||
    limit > 250
  ) {
    throw new Error("Use --offset >= 0 and --limit between 1 and 250");
  }
  if (revision !== "main" && !/^[0-9a-f]{40}$/.test(revision)) {
    throw new Error("Use --revision main or an exact 40-character Git SHA");
  }
  return { offset, limit, apply, revision };
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : "Import failed";
  console.error(message);
  process.exitCode = 1;
});
