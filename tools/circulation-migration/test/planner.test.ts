import assert from "node:assert/strict";
import {
  chmod,
  mkdtemp,
  readFile,
  rename,
  rm,
  stat,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import {
  readJsonArtifact,
  readSecretUrlFile,
  reserveJsonArtifact,
  writeJsonArtifact,
} from "../src/artifacts.js";
import { sha256 } from "../src/canonical.js";
import { assertLocalPostgresUrl } from "../src/database.js";
import { buildPlan, verifyPlan } from "../src/planner.js";
import { reconcile, verifyReconciliationReport } from "../src/reconcile.js";
import type { LegacySnapshot } from "../src/types.js";

const branchId = "99999999-9999-4999-8999-999999999999";

async function fixture(name: string): Promise<unknown> {
  return JSON.parse(
    await readFile(
      fileURLToPath(new URL(`fixtures/${name}`, import.meta.url)),
      "utf8",
    ),
  ) as unknown;
}

test("redacted seeded inventory blocker evidence is checksum-bound", async () => {
  const value = JSON.parse(
    await readFile(
      fileURLToPath(
        new URL(
          "../evidence/seeded-inventory-cutover-blocker.json",
          import.meta.url,
        ),
      ),
      "utf8",
    ),
  ) as Record<string, unknown>;
  const { evidenceSha256, ...draft } = value;
  assert.equal(evidenceSha256, sha256(draft));
  assert.equal(value.status, "BLOCKED");
  assert.equal(value.containsPersonalData, false);
  const editions = value.editions as Array<Record<string, unknown>>;
  assert.equal(editions.length, 5);
  for (const edition of editions) {
    assert.deepEqual(Object.keys(edition).sort(), [
      "editionId",
      "sourceAvailableCopies",
      "sourceOpenLoans",
      "sourceTotalCopies",
      "unexplainedUnavailableCopies",
    ]);
  }
});

test("planner is deterministic and maps a feasible snapshot", async () => {
  const snapshot = await fixture("valid-snapshot.json");
  const options = {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  };
  const first = buildPlan(snapshot, options);
  const second = buildPlan(snapshot, options);

  assert.deepEqual(second, first);
  assert.equal(first.reconciliation.status, "READY");
  assert.equal(first.target.copies.length, 3);
  assert.equal(first.target.loans.length, 3);
  assert.equal(
    first.target.copies.filter((copy) => copy.status === "ON_LOAN").length,
    1,
  );
  assert.equal(
    first.target.loans.find((loan) => loan.status === "RETURNED")?.returnedAt,
    "2025-01-10T23:59:59.999Z",
  );
  assert.match(first.target.copies[0]!.barcode, /^LEGACY-99999999-/);
  assert.equal(first.planSha256.length, 64);
  assert.equal(verifyPlan(first).planSha256, first.planSha256);
});

test("ambiguous physical-copy identity blocks planning by default", async () => {
  const plan = buildPlan(await fixture("valid-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    fineMigrationPolicyAcknowledged: true,
  });
  assert.equal(plan.reconciliation.status, "BLOCKED");
  assert.ok(
    plan.findings.some(
      (finding) =>
        finding.code === "HISTORICAL_COPY_IDENTITY_AMBIGUOUS" &&
        finding.severity === "ERROR",
    ),
  );
});

test("impossible inventory and loan timelines are machine-detectable", async () => {
  const plan = buildPlan(await fixture("impossible-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  const codes = new Set(plan.findings.map((finding) => finding.code));
  assert.equal(plan.reconciliation.status, "BLOCKED");
  assert.ok(codes.has("COPY_TIMELINE_OVER_CAPACITY"));
  assert.ok(codes.has("AVAILABLE_COUNTER_DRIFT"));
  assert.ok(codes.has("LOAN_DUE_DATE_INVALID"));
  assert.equal(plan.target.loans.length, 1);
  assert.equal(verifyPlan(plan).reconciliation.status, "BLOCKED");
});

test("plan integrity detects any post-plan mutation", async () => {
  const plan = buildPlan(await fixture("valid-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  const tampered = structuredClone(plan);
  tampered.target.copies[0]!.barcode = "TAMPERED";
  assert.throws(() => verifyPlan(tampered), /integrity check failed/);
});

test("semantic validation rejects a self-consistently rehashed unsafe plan", async () => {
  const plan = buildPlan(await fixture("valid-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  const unsafe = structuredClone(plan);
  unsafe.target.copies[0]!.barcode = "ATTACKER-CONTROLLED";
  const { planSha256: _oldHash, ...draft } = unsafe;
  unsafe.planSha256 = sha256(draft);
  assert.throws(
    () => verifyPlan(unsafe),
    /deterministic copy ordinal .* is absent/,
  );
});

test("renewals and outstanding MAD fines map exactly into phase-2 state", async () => {
  const snapshot = structuredClone(
    await fixture("valid-snapshot.json"),
  ) as LegacySnapshot;
  const active = snapshot.borrowRecords.find(
    (record) => record.status === "BORROWED",
  )!;
  active.renewalCount = 2;
  active.fineAmount = "12.34";
  active.updatedAt = "2026-03-01T09:00:00.000Z";

  const plan = buildPlan(snapshot, {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });

  assert.equal(plan.reconciliation.status, "READY");
  const loan = plan.target.loans.find((row) => row.id === active.id)!;
  assert.equal(loan.renewalCount, 2);
  assert.equal(loan.version, 3);
  assert.equal(plan.source.renewalCountTotal, "2");
  assert.equal(plan.source.nonzeroFineCount, 1);
  assert.equal(plan.source.fineBalanceMinorTotal, "1234");
  assert.equal(plan.target.fines.length, 1);
  assert.equal(plan.target.fines[0]!.balanceMinor, 1234);
  assert.equal(plan.target.fines[0]!.currency, "MAD");
  assert.equal(plan.target.fines[0]!.loanId, loan.id);
  assert.equal(plan.target.fineLedgerEntries.length, 1);
  assert.equal(plan.target.fineLedgerEntries[0]!.deltaMinor, 1234);
  assert.equal(
    plan.target.fineLedgerEntries[0]!.fineId,
    plan.target.fines[0]!.id,
  );
  assert.equal(verifyPlan(plan).planSha256, plan.planSha256);
});

test("ambiguous money and unsupported renewal counts hard-block", async () => {
  const moneySnapshot = structuredClone(
    await fixture("valid-snapshot.json"),
  ) as LegacySnapshot;
  moneySnapshot.borrowRecords[0]!.fineAmount = "1.001";
  const moneyPlan = buildPlan(moneySnapshot, {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  assert.equal(moneyPlan.reconciliation.status, "BLOCKED");
  assert.ok(
    moneyPlan.findings.some(
      (finding) => finding.code === "LOAN_FINE_AMOUNT_INVALID",
    ),
  );

  const renewalSnapshot = structuredClone(
    await fixture("valid-snapshot.json"),
  ) as LegacySnapshot;
  renewalSnapshot.borrowRecords[0]!.renewalCount = 101;
  const renewalPlan = buildPlan(renewalSnapshot, {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  assert.equal(renewalPlan.reconciliation.status, "BLOCKED");
  assert.ok(
    renewalPlan.findings.some(
      (finding) => finding.code === "LOAN_RENEWAL_COUNT_INVALID",
    ),
  );
});

test("legacy metadata without an approved target destination hard-blocks", async () => {
  const snapshot = structuredClone(
    await fixture("valid-snapshot.json"),
  ) as LegacySnapshot;
  snapshot.borrowRecords[0]!.notes = "Must remain queryable after cutover";
  const plan = buildPlan(snapshot, {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  assert.equal(plan.reconciliation.status, "BLOCKED");
  assert.ok(
    plan.findings.some(
      (finding) =>
        finding.code === "LEGACY_METADATA_DESTINATION_UNRESOLVED" &&
        finding.severity === "ERROR",
    ),
  );
});

test("reconciliation is exact and emits field-level evidence", async () => {
  const plan = buildPlan(await fixture("valid-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
    fineMigrationPolicyAcknowledged: true,
  });
  const matching = reconcile({
    plan,
    actualCopies: structuredClone(plan.target.copies),
    actualLoans: structuredClone(plan.target.loans),
    actualFines: structuredClone(plan.target.fines),
    actualFineLedgerEntries: structuredClone(
      plan.target.fineLedgerEntries,
    ),
    observedAt: "2026-07-26T11:00:00.000Z",
    database: "circulation",
    serverVersion: "18.0",
  });
  assert.equal(matching.status, "MATCH");
  assert.equal(matching.mismatches.length, 0);
  assert.equal(
    verifyReconciliationReport(matching).reportSha256,
    matching.reportSha256,
  );
  assert.equal(
    verifyReconciliationReport(matching, plan).reportSha256,
    matching.reportSha256,
  );

  const actualCopies = structuredClone(plan.target.copies);
  actualCopies[0]!.barcode = "WRONG";
  actualCopies.pop();
  const mismatch = reconcile({
    plan,
    actualCopies,
    actualLoans: structuredClone(plan.target.loans),
    actualFines: structuredClone(plan.target.fines),
    actualFineLedgerEntries: structuredClone(
      plan.target.fineLedgerEntries,
    ),
    observedAt: "2026-07-26T11:00:00.000Z",
    database: "circulation",
    serverVersion: "18.0",
  });
  assert.equal(mismatch.status, "MISMATCH");
  assert.ok(mismatch.mismatches.some((row) => row.kind === "MISSING"));
  assert.ok(
    mismatch.mismatches.some(
      (row) =>
        row.kind === "FIELD_MISMATCH" &&
        row.differingFields.includes("barcode"),
    ),
  );
  const tampered = structuredClone(matching);
  tampered.target.database = "other";
  assert.throws(
    () => verifyReconciliationReport(tampered),
    /integrity check failed/,
  );

  const rebound = structuredClone(matching);
  rebound.planSha256 = "0".repeat(64);
  const { reportSha256: _oldReportHash, ...reportDraft } = rebound;
  rebound.reportSha256 = sha256(reportDraft);
  assert.throws(
    () => verifyReconciliationReport(rebound, plan),
    /does not bind to the supplied plan/,
  );
});

test("database guard accepts only direct loopback URLs without parameters", () => {
  assert.equal(
    assertLocalPostgresUrl(
      "postgresql://migration:secret@127.0.0.1:55439/circulation",
    ).hostname,
    "127.0.0.1",
  );
  assert.throws(
    () =>
      assertLocalPostgresUrl(
        "postgresql://migration:secret@db.example.com/circulation",
      ),
    /only literal/,
  );
  assert.throws(
    () =>
      assertLocalPostgresUrl(
        "postgresql://migration:secret@localhost/circulation",
      ),
    /only literal/,
  );
  assert.throws(
    () =>
      assertLocalPostgresUrl(
        "postgresql://migration:secret@127.0.0.1/circulation?host=db.example.com",
      ),
    /query parameters and fragments are forbidden/,
  );
  assert.throws(
    () =>
      assertLocalPostgresUrl(
        "postgresql://migration:secret@127.0.0.1/circulation#other",
      ),
    /fragments are forbidden/,
  );
});

test("artifact creation is private and cannot overwrite an existing path", async () => {
  const directory = await mkdtemp(join(tmpdir(), "circulation-artifact-"));
  const output = join(directory, "plan.json");
  try {
    await writeJsonArtifact(output, { first: true });
    assert.equal((await stat(output)).mode & 0o777, 0o600);
    await assert.rejects(
      () => writeJsonArtifact(output, { second: true }),
      /Refusing to overwrite/,
    );
    assert.deepEqual(JSON.parse(await readFile(output, "utf8")), {
      first: true,
    });
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("apply evidence is reserved before work and completed in place", async () => {
  const directory = await mkdtemp(join(tmpdir(), "circulation-reservation-"));
  const output = join(directory, "apply.json");
  try {
    const reservation = await reserveJsonArtifact(output, {
      status: "PENDING",
    });
    assert.deepEqual(JSON.parse(await readFile(output, "utf8")), {
      status: "PENDING",
    });
    await reservation.complete({ status: "MATCH" });
    assert.deepEqual(JSON.parse(await readFile(output, "utf8")), {
      status: "MATCH",
    });
    await assert.rejects(
      () => reservation.complete({ status: "SECOND" }),
      /already complete/,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("artifact reads reject symlinks, permissive files, and swapped reservations", async () => {
  const directory = await mkdtemp(join(tmpdir(), "circulation-artifact-guard-"));
  const target = join(directory, "target.json");
  const link = join(directory, "link.json");
  const reservationPath = join(directory, "apply.json");
  const displaced = join(directory, "apply.displaced.json");
  try {
    await writeJsonArtifact(target, { safe: true });
    await symlink(target, link);
    await assert.rejects(
      () => readJsonArtifact(link),
      /symbolic-link artifact/,
    );

    await chmod(target, 0o644);
    await assert.rejects(
      () => readJsonArtifact(target),
      /must not grant group or other permissions/,
    );
    await chmod(target, 0o600);

    const reservation = await reserveJsonArtifact(reservationPath, {
      status: "PENDING",
    });
    await rename(reservationPath, displaced);
    await writeJsonArtifact(reservationPath, { status: "IMPOSTER" });
    await assert.rejects(
      () => reservation.complete({ status: "MATCH" }),
      /identity or permissions changed/,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});

test("database URL secret files are private single-line inputs", async () => {
  const directory = await mkdtemp(join(tmpdir(), "circulation-url-secret-"));
  const secret = join(directory, "database-url");
  try {
    await writeFile(
      secret,
      "postgresql://migration:encoded@127.0.0.1:55439/circulation\n",
      { encoding: "utf8", flag: "wx", mode: 0o600 },
    );
    assert.equal(
      await readSecretUrlFile(secret),
      "postgresql://migration:encoded@127.0.0.1:55439/circulation",
    );
    await chmod(secret, 0o644);
    await assert.rejects(
      () => readSecretUrlFile(secret),
      /must not grant group or other permissions/,
    );
  } finally {
    await rm(directory, { recursive: true });
  }
});
