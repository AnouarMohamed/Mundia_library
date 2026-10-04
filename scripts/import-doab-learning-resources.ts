import dotenv from "dotenv";
import { fetchDoabPage } from "../lib/learning-resources/doab";

dotenv.config({ path: ".env.local", override: false, quiet: true });
dotenv.config({ override: false, quiet: true });

const SOURCE_NAME = "Directory of Open Access Books";

interface Arguments {
  from?: string;
  token?: string;
  expectedRevision?: string;
  offset: number;
  limit: number;
  apply: boolean;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const page = await fetchDoabPage({
    from: args.from,
    resumptionToken: args.token,
  });
  const candidates = page.candidates.slice(0, args.limit);
  const summary = {
    source: SOURCE_NAME,
    revision: page.revision,
    sourceRecords: page.sourceRecords,
    selected: candidates.length,
    offset: args.offset,
    limit: args.limit,
    verified: candidates.filter(
      (candidate) => candidate.verificationStatus === "VERIFIED",
    ).length,
    quarantined: candidates.filter(
      (candidate) => candidate.verificationStatus === "QUARANTINED",
    ).length,
    nextToken: page.nextToken,
  };
  if (!args.apply) {
    console.log(JSON.stringify({ mode: "dry-run", ...summary }));
    return;
  }
  if (!process.env.DATABASE_URL) {
    throw new Error("DATABASE_URL is required for --apply");
  }
  if (!args.expectedRevision || args.expectedRevision !== page.revision) {
    throw new Error("DOAB page changed; dry-run again and use --expected-revision");
  }

  const [{ importLearningResourceBatch }, { closeDb }] = await Promise.all([
    import("../lib/learning-resources/import-service"),
    import("../database/drizzle"),
  ]);
  try {
    const result = await importLearningResourceBatch({
      sourceName: SOURCE_NAME,
      sourceRevision: page.revision,
      offset: args.offset,
      limit: args.limit,
      candidates,
    });
    console.log(JSON.stringify({ mode: "applied", ...summary, result }));
  } finally {
    await closeDb();
  }
}

function parseArguments(values: string[]): Arguments {
  const args: Arguments = { offset: 0, limit: 250, apply: false };
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (value === "--apply") {
      args.apply = true;
      continue;
    }
    const next = values[index + 1];
    if (!next) throw new Error(`Missing value for ${value}`);
    if (value === "--from") args.from = next;
    else if (value === "--token") args.token = next;
    else if (value === "--expected-revision") args.expectedRevision = next;
    else if (value === "--offset") args.offset = Number(next);
    else if (value === "--limit") args.limit = Number(next);
    else throw new Error(`Unknown argument: ${value}`);
    index += 1;
  }
  if (args.from && args.token) throw new Error("Use --from or --token, not both");
  if (!Number.isSafeInteger(args.offset) || args.offset < 0) {
    throw new Error("Use --offset >= 0");
  }
  if (!Number.isSafeInteger(args.limit) || args.limit < 1 || args.limit > 250) {
    throw new Error("Use --limit between 1 and 250");
  }
  if (
    args.expectedRevision &&
    !/^[0-9a-f]{64}$/.test(args.expectedRevision)
  ) {
    throw new Error("Use a 64-character --expected-revision from dry-run");
  }
  return args;
}

main().catch((error: unknown) => {
  const message = error instanceof Error ? error.message : "Import failed";
  console.error(message);
  process.exitCode = 1;
});
