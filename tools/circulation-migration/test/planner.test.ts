import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { reserveJsonArtifact, writeJsonArtifact } from "../src/artifacts.js";
import { sha256 } from "../src/canonical.js";
import { assertLocalPostgresUrl } from "../src/database.js";
import { buildPlan, verifyPlan } from "../src/planner.js";
import { reconcile, verifyReconciliationReport } from "../src/reconcile.js";

const branchId = "99999999-9999-4999-8999-999999999999";

async function fixture(name: string): Promise<unknown> {
  return JSON.parse(
    await readFile(
      fileURLToPath(new URL(`fixtures/${name}`, import.meta.url)),
      "utf8",
    ),
  ) as unknown;
}

test("planner is deterministic and maps a feasible snapshot", async () => {
  const snapshot = await fixture("valid-snapshot.json");
  const options = {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
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

test("reconciliation is exact and emits field-level evidence", async () => {
  const plan = buildPlan(await fixture("valid-snapshot.json"), {
    branchId,
    preserveLegacyIdentifiersAcknowledged: true,
    allowSyntheticHistoricalCopyAssignment: true,
  });
  const matching = reconcile({
    plan,
    actualCopies: structuredClone(plan.target.copies),
    actualLoans: structuredClone(plan.target.loans),
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

  const actualCopies = structuredClone(plan.target.copies);
  actualCopies[0]!.barcode = "WRONG";
  actualCopies.pop();
  const mismatch = reconcile({
    plan,
    actualCopies,
    actualLoans: structuredClone(plan.target.loans),
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
    /only localhost/,
  );
  assert.throws(
    () =>
      assertLocalPostgresUrl(
        "postgresql://migration:secret@localhost/circulation?host=db.example.com",
      ),
    /query parameters are forbidden/,
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
