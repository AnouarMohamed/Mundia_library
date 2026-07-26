#!/usr/bin/env node
import {
  readJsonArtifact,
  readSecretUrlFile,
  reserveJsonArtifact,
  writeJsonArtifact,
} from "./artifacts.js";
import { sha256 } from "./canonical.js";
import { applyPlan, reconcileTarget, snapshotLegacy } from "./database.js";
import { buildPlan, verifyPlan } from "./planner.js";
import { verifyReconciliationReport } from "./reconcile.js";

const HELP = `
Mundiapolis legacy-to-circulation migration

No command writes to a database except the explicitly guarded "apply" command.
No command accepts a non-loopback database URL.

Commands:
  snapshot  Capture a serializable, read-only, deferrable legacy snapshot.
  plan      Create a deterministic dry-run migration plan.
  reconcile Compare a plan with target rows using a read-only transaction.
  verify    Verify a plan or reconciliation artifact offline.
  apply     Insert target-only rows transactionally; never updates legacy data.

Run a command with --help for examples and required safety flags.
`.trim();

interface ParsedArguments {
  command: string | undefined;
  options: Map<string, string | true>;
}

function parseArguments(argv: string[]): ParsedArguments {
  const [command, ...rest] = argv;
  const options = new Map<string, string | true>();
  for (let index = 0; index < rest.length; index += 1) {
    const token = rest[index]!;
    if (!token.startsWith("--")) {
      throw new Error(`Unexpected positional argument: ${token}`);
    }
    const key = token.slice(2);
    if (options.has(key)) throw new Error(`Duplicate option: --${key}`);
    const next = rest[index + 1];
    if (next && !next.startsWith("--")) {
      options.set(key, next);
      index += 1;
    } else {
      options.set(key, true);
    }
  }
  return { command, options };
}

function assertAllowed(
  options: Map<string, string | true>,
  allowed: string[],
): void {
  const set = new Set(allowed);
  for (const key of options.keys()) {
    if (!set.has(key)) throw new Error(`Unknown option: --${key}`);
  }
}

function required(options: Map<string, string | true>, key: string): string {
  const value = options.get(key);
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`--${key} is required and must have a value`);
  }
  return value;
}

function exact(
  options: Map<string, string | true>,
  key: string,
  expected: string,
): void {
  const value = required(options, key);
  if (value !== expected) {
    throw new Error(`--${key} must equal ${expected}`);
  }
}

function flag(options: Map<string, string | true>, key: string): boolean {
  return options.get(key) === true;
}

function print(value: unknown): void {
  process.stdout.write(`${JSON.stringify(value)}\n`);
}

function redactError(message: string): string {
  return message.replace(
    /\b(postgres(?:ql)?:\/\/)[^\s]*@/gi,
    "$1***@",
  );
}

async function snapshot(options: Map<string, string | true>): Promise<void> {
  assertAllowed(options, [
    "help",
    "source-url-file",
    "expect-database",
    "out",
  ]);
  if (flag(options, "help")) {
    process.stdout.write(
      "snapshot --source-url-file /secure/source-url --expect-database DB --out snapshot.json\n",
    );
    return;
  }
  const result = await snapshotLegacy({
    sourceUrl: await readSecretUrlFile(
      required(options, "source-url-file"),
    ),
    expectedDatabase: required(options, "expect-database"),
  });
  const output = required(options, "out");
  await writeJsonArtifact(output, result);
  print({
    status: "SNAPSHOT_WRITTEN",
    output,
    books: result.books.length,
    borrowRecords: result.borrowRecords.length,
    databaseWrites: 0,
  });
}

async function plan(options: Map<string, string | true>): Promise<void> {
  assertAllowed(options, [
    "help",
    "snapshot",
    "branch-id",
    "identifier-policy",
    "uuid-namespace",
    "allow-synthetic-historical-copy-assignment",
    "fine-currency",
    "legacy-null-fine-policy",
    "legacy-fine-balance-policy",
    "fine-assessment-time-policy",
    "out",
  ]);
  if (flag(options, "help")) {
    process.stdout.write(
      [
        "plan --snapshot snapshot.json --branch-id UUID --identifier-policy preserve-legacy-uuids --out plan.json",
        "Optional explicit policies:",
        "  --allow-synthetic-historical-copy-assignment",
        "Required finance-owner decisions:",
        "  --fine-currency MAD",
        "  --legacy-null-fine-policy no-fine",
        "  --legacy-fine-balance-policy current-outstanding-as-initial-assessment",
        "  --fine-assessment-time-policy legacy-updated-at",
      ].join("\n") + "\n",
    );
    return;
  }
  exact(options, "identifier-policy", "preserve-legacy-uuids");
  exact(options, "fine-currency", "MAD");
  exact(options, "legacy-null-fine-policy", "no-fine");
  exact(
    options,
    "legacy-fine-balance-policy",
    "current-outstanding-as-initial-assessment",
  );
  exact(
    options,
    "fine-assessment-time-policy",
    "legacy-updated-at",
  );
  const raw = await readJsonArtifact(required(options, "snapshot"));
  const namespace = options.get("uuid-namespace");
  const result = buildPlan(raw, {
    branchId: required(options, "branch-id"),
    ...(typeof namespace === "string"
      ? { deterministicUuidNamespace: namespace }
      : {}),
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: flag(
      options,
      "allow-synthetic-historical-copy-assignment",
    ),
    fineMigrationPolicyAcknowledged: true,
  });
  const output = required(options, "out");
  await writeJsonArtifact(output, result);
  print({
    status: result.reconciliation.status,
    output,
    planSha256: result.planSha256,
    errors: result.findings.filter((finding) => finding.severity === "ERROR")
      .length,
    warnings: result.findings.filter(
      (finding) => finding.severity === "WARNING",
    ).length,
    databaseWrites: 0,
  });
  if (result.reconciliation.status === "BLOCKED") process.exitCode = 2;
}

async function reconcileCommand(
  options: Map<string, string | true>,
): Promise<void> {
  assertAllowed(options, [
    "help",
    "plan",
    "target-url-file",
    "expect-database",
    "out",
  ]);
  if (flag(options, "help")) {
    process.stdout.write(
      "reconcile --plan plan.json --target-url-file /secure/target-url --expect-database DB --out reconciliation.json\n",
    );
    return;
  }
  const planValue = verifyPlan(
    await readJsonArtifact(required(options, "plan")),
  );
  const result = await reconcileTarget({
    plan: planValue,
    targetUrl: await readSecretUrlFile(
      required(options, "target-url-file"),
    ),
    expectedDatabase: required(options, "expect-database"),
  });
  const output = required(options, "out");
  await writeJsonArtifact(output, result);
  print({
    status: result.status,
    output,
    planSha256: result.planSha256,
    reportSha256: result.reportSha256,
    mismatchCount: result.mismatches.length,
    databaseWrites: 0,
  });
  if (result.status !== "MATCH") process.exitCode = 2;
}

async function verify(options: Map<string, string | true>): Promise<void> {
  assertAllowed(options, ["help", "plan", "report"]);
  if (flag(options, "help")) {
    process.stdout.write(
      [
        "verify --plan plan.json",
        "verify --report reconciliation.json --plan plan.json",
      ].join("\n") + "\n",
    );
    return;
  }
  const planPath = options.get("plan");
  const reportPath = options.get("report");
  if (typeof reportPath !== "string" && typeof planPath === "string") {
    const result = verifyPlan(await readJsonArtifact(planPath));
    print({
      status: "VALID",
      artifact: "PLAN",
      planStatus: result.reconciliation.status,
      planSha256: result.planSha256,
      databaseWrites: 0,
    });
    return;
  }
  if (typeof reportPath !== "string" || typeof planPath !== "string") {
    throw new Error(
      "Supply --plan alone, or supply both --report and its --plan",
    );
  }
  const expectedPlan = verifyPlan(await readJsonArtifact(planPath));
  const result = verifyReconciliationReport(
    await readJsonArtifact(reportPath),
    expectedPlan,
  );
  print({
    status: "VALID",
    artifact: "RECONCILIATION",
    reconciliationStatus: result.status,
    planSha256: result.planSha256,
    reportSha256: result.reportSha256,
    databaseWrites: 0,
  });
}

async function apply(options: Map<string, string | true>): Promise<void> {
  assertAllowed(options, [
    "help",
    "plan",
    "target-url-file",
    "expect-database",
    "expect-plan-sha256",
    "cutover-state",
    "target-writer-state",
    "allow-target-writes",
    "evidence",
  ]);
  if (flag(options, "help")) {
    process.stdout.write(
      [
        "CIRCULATION_MIGRATION_WRITE_ACK=TARGET_ONLY_NO_DUAL_WRITE \\",
        "  npm run cli -- apply \\",
        "  --plan final-plan.json \\",
        "  --target-url-file /secure/target-url \\",
        "  --expect-database DB --expect-plan-sha256 SHA256 \\",
        "  --cutover-state legacy-writes-frozen \\",
        "  --target-writer-state stopped --allow-target-writes \\",
        "  --evidence application-reconciliation.json",
      ].join("\n") + "\n",
    );
    return;
  }
  if (!flag(options, "allow-target-writes")) {
    throw new Error("--allow-target-writes must be supplied as a flag");
  }
  exact(options, "cutover-state", "legacy-writes-frozen");
  exact(options, "target-writer-state", "stopped");
  const planValue = verifyPlan(
    await readJsonArtifact(required(options, "plan")),
  );
  const expectedHash = required(options, "expect-plan-sha256");
  if (expectedHash !== planValue.planSha256) {
    throw new Error(
      "--expect-plan-sha256 does not match the integrity-checked plan",
    );
  }
  const output = required(options, "evidence");
  const targetUrl = await readSecretUrlFile(
    required(options, "target-url-file"),
  );
  const expectedDatabase = required(options, "expect-database");
  const reservation = await reserveJsonArtifact(output, {
    schemaVersion: "circulation-application-pending/v1",
    status: "PENDING",
    planSha256: planValue.planSha256,
    targetDatabase: expectedDatabase,
    createdAt: new Date().toISOString(),
    instruction:
      "Application outcome is not proven. Keep both writers stopped and run read-only reconciliation.",
  });
  let result;
  try {
    result = await applyPlan({
      plan: planValue,
      targetUrl,
      expectedDatabase,
      writeAcknowledgement: process.env.CIRCULATION_MIGRATION_WRITE_ACK,
    });
  } catch (error) {
    const safeMessage = redactError(
      error instanceof Error ? error.message : "Unknown apply failure",
    );
    const failure = {
      schemaVersion: "circulation-application-failure/v1",
      status: "UNKNOWN_REQUIRES_RECONCILIATION",
      planSha256: planValue.planSha256,
      targetDatabase: expectedDatabase,
      occurredAt: new Date().toISOString(),
      error: safeMessage,
      instruction:
        "Keep both writers stopped and run read-only reconciliation before retry or rollback.",
    };
    await reservation.complete({ ...failure, failureSha256: sha256(failure) });
    throw error;
  }
  await reservation.complete(result);
  print({
    status: result.status,
    output,
    planSha256: result.planSha256,
    reportSha256: result.reportSha256,
    transactionOutcome: result.application?.transactionOutcome,
    insertedCopies: result.application?.insertedCopies,
    insertedLoans: result.application?.insertedLoans,
    insertedFines: result.application?.insertedFines,
    insertedFineLedgerEntries:
      result.application?.insertedFineLedgerEntries,
  });
  if (
    result.status !== "MATCH" ||
    result.application?.transactionOutcome !== "COMMITTED"
  ) {
    process.exitCode = 2;
  }
}

async function main(): Promise<void> {
  const parsed = parseArguments(process.argv.slice(2));
  if (!parsed.command || parsed.command === "--help") {
    process.stdout.write(`${HELP}\n`);
    return;
  }
  switch (parsed.command) {
    case "snapshot":
      await snapshot(parsed.options);
      break;
    case "plan":
      await plan(parsed.options);
      break;
    case "reconcile":
      await reconcileCommand(parsed.options);
      break;
    case "verify":
      await verify(parsed.options);
      break;
    case "apply":
      await apply(parsed.options);
      break;
    default:
      throw new Error(`Unknown command: ${parsed.command}`);
  }
}

main().catch((error: unknown) => {
  const message = redactError(
    error instanceof Error ? error.message : "Unknown failure",
  );
  process.stderr.write(
    `${JSON.stringify({
      status: "ERROR",
      error: message,
      databaseWrites: "NOT_CONFIRMED",
    })}\n`,
  );
  process.exitCode = 1;
});
