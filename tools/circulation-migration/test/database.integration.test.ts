import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { Client } from "pg";
import {
  applyPlan,
  assertLocalPostgresUrl,
  reconcileTarget,
} from "../src/database.js";
import { buildPlan } from "../src/planner.js";
import type { LegacySnapshot } from "../src/types.js";

const targetUrl = process.env.CIRCULATION_MIGRATION_TEST_URL;
const expectedDatabase =
  process.env.CIRCULATION_MIGRATION_TEST_DATABASE;
const integrationEnabled =
  typeof targetUrl === "string" &&
  targetUrl.length > 0 &&
  typeof expectedDatabase === "string" &&
  expectedDatabase.length > 0;
if (
  process.env.CIRCULATION_MIGRATION_REQUIRE_INTEGRATION === "true" &&
  !integrationEnabled
) {
  throw new Error(
    "Required migration integration test needs CIRCULATION_MIGRATION_TEST_URL and CIRCULATION_MIGRATION_TEST_DATABASE",
  );
}

async function snapshot(): Promise<LegacySnapshot> {
  return JSON.parse(
    await readFile(
      fileURLToPath(
        new URL("fixtures/valid-snapshot.json", import.meta.url),
      ),
      "utf8",
    ),
  ) as LegacySnapshot;
}

test(
  "PostgreSQL 18 apply is exact, replayable, conflict-rollback safe, and trigger guarded",
  { skip: !integrationEnabled },
  async () => {
    const url = targetUrl!;
    const database = expectedDatabase!;
    assertLocalPostgresUrl(url);
    const source = await snapshot();
    const active = source.borrowRecords.find(
      (record) => record.status === "BORROWED",
    )!;
    active.renewalCount = 2;
    active.fineAmount = "12.34";
    active.updatedAt = "2026-03-01T09:00:00.000Z";

    const options = {
      preserveLegacyIdentifiersAcknowledged: true,
      allowSyntheticHistoricalCopyAssignment: true,
      fineMigrationPolicyAcknowledged: true,
    };
    const plan = buildPlan(source, {
      ...options,
      branchId: "99999999-9999-4999-8999-999999999999",
    });
    assert.equal(plan.reconciliation.status, "READY");

    const concurrent = await Promise.all(
      [0, 1].map(() =>
        applyPlan({
          plan,
          targetUrl: url,
          expectedDatabase: database,
          writeAcknowledgement: "TARGET_ONLY_NO_DUAL_WRITE",
        }),
      ),
    );
    assert.ok(
      concurrent.every(
        (report) =>
          report.status === "MATCH" &&
          report.application?.transactionOutcome === "COMMITTED",
      ),
    );
    assert.equal(
      concurrent.reduce(
        (total, report) =>
          total + (report.application?.insertedCopies ?? 0),
        0,
      ),
      3,
    );
    assert.equal(
      concurrent.reduce(
        (total, report) =>
          total + (report.application?.insertedLoans ?? 0),
        0,
      ),
      3,
    );
    assert.equal(
      concurrent.reduce(
        (total, report) =>
          total + (report.application?.insertedFines ?? 0),
        0,
      ),
      1,
    );
    assert.equal(
      concurrent.reduce(
        (total, report) =>
          total +
          (report.application?.insertedFineLedgerEntries ?? 0),
        0,
      ),
      1,
    );

    const replay = await applyPlan({
      plan,
      targetUrl: url,
      expectedDatabase: database,
      writeAcknowledgement: "TARGET_ONLY_NO_DUAL_WRITE",
    });
    assert.equal(replay.status, "MATCH");
    assert.equal(replay.application?.transactionOutcome, "COMMITTED");
    assert.equal(replay.application?.insertedCopies, 0);
    assert.equal(replay.application?.insertedLoans, 0);
    assert.equal(replay.application?.insertedFines, 0);
    assert.equal(replay.application?.insertedFineLedgerEntries, 0);

    const conflictingPlan = buildPlan(source, {
      ...options,
      branchId: "88888888-8888-4888-8888-888888888888",
    });
    const conflict = await applyPlan({
      plan: conflictingPlan,
      targetUrl: url,
      expectedDatabase: database,
      writeAcknowledgement: "TARGET_ONLY_NO_DUAL_WRITE",
    });
    assert.equal(conflict.status, "MISMATCH");
    assert.equal(conflict.application?.transactionOutcome, "ROLLED_BACK");

    const afterConflict = await reconcileTarget({
      plan,
      targetUrl: url,
      expectedDatabase: database,
    });
    assert.equal(afterConflict.status, "MATCH");

    const parsed = assertLocalPostgresUrl(url);
    const client = new Client({
      host: parsed.hostname === "[::1]" ? "::1" : parsed.hostname,
      port: parsed.port === "" ? 5432 : Number(parsed.port),
      user: decodeURIComponent(parsed.username),
      password: decodeURIComponent(parsed.password),
      database: decodeURIComponent(parsed.pathname).replace(/^\//, ""),
      ssl: false,
    });
    await client.connect();
    try {
      await assert.rejects(
        () =>
          client.query(
            "UPDATE circulation_fine_ledger_entry SET delta_minor = delta_minor + 1",
          ),
        /immutable/,
      );

      await client.query(
        "ALTER TABLE circulation_fine_ledger_entry DISABLE TRIGGER trg_circulation_fine_ledger_no_update_delete",
      );
      await assert.rejects(
        () =>
          reconcileTarget({
            plan,
            targetUrl: url,
            expectedDatabase: database,
          }),
        /trigger is absent or disabled/,
      );
    } finally {
      await client
        .query(
          "ALTER TABLE circulation_fine_ledger_entry ENABLE TRIGGER trg_circulation_fine_ledger_no_update_delete",
        )
        .catch(() => undefined);
      await client.end();
    }
  },
);
