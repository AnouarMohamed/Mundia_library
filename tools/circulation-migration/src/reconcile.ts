import { sha256 } from "./canonical.js";
import {
  RECONCILIATION_SCHEMA,
  type MigrationPlan,
  type ReconciliationReport,
  type ReconciliationReportWithoutHash,
  type RowMismatch,
  type TargetCopy,
  type TargetFine,
  type TargetFineLedgerEntry,
  type TargetLoan,
} from "./types.js";

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function differingFields(
  expected: Record<string, unknown>,
  actual: Record<string, unknown>,
): string[] {
  return [...new Set([...Object.keys(expected), ...Object.keys(actual)])]
    .filter(
      (key) =>
        sha256({
          present: Object.hasOwn(expected, key),
          value: expected[key] ?? null,
        }) !==
        sha256({
          present: Object.hasOwn(actual, key),
          value: actual[key] ?? null,
        }),
    )
    .sort();
}

function compareRows<T extends { id: string }>(
  table: RowMismatch["table"],
  expectedRows: T[],
  actualRows: T[],
): RowMismatch[] {
  const mismatches: RowMismatch[] = [];
  const expected = new Map(expectedRows.map((row) => [row.id, row]));
  const actual = new Map(actualRows.map((row) => [row.id, row]));
  const ids = [...new Set([...expected.keys(), ...actual.keys()])].sort();

  for (const id of ids) {
    const expectedRow = expected.get(id);
    const actualRow = actual.get(id);
    if (!expectedRow) {
      mismatches.push({
        table,
        rowId: id,
        kind: "UNEXPECTED",
        differingFields: [],
        expectedSha256: null,
        actualSha256: sha256(actualRow),
      });
    } else if (!actualRow) {
      mismatches.push({
        table,
        rowId: id,
        kind: "MISSING",
        differingFields: [],
        expectedSha256: sha256(expectedRow),
        actualSha256: null,
      });
    } else if (sha256(expectedRow) !== sha256(actualRow)) {
      mismatches.push({
        table,
        rowId: id,
        kind: "FIELD_MISMATCH",
        differingFields: differingFields(
          expectedRow as Record<string, unknown>,
          actualRow as Record<string, unknown>,
        ),
        expectedSha256: sha256(expectedRow),
        actualSha256: sha256(actualRow),
      });
    }
  }
  return mismatches;
}

export interface ReconcileInput {
  plan: MigrationPlan;
  actualCopies: TargetCopy[];
  actualLoans: TargetLoan[];
  actualFines: TargetFine[];
  actualFineLedgerEntries: TargetFineLedgerEntry[];
  observedAt: string;
  database: string;
  serverVersion: string;
  application?: ReconciliationReportWithoutHash["application"];
}

export function reconcile(input: ReconcileInput): ReconciliationReport {
  const mismatches = [
    ...compareRows(
      "circulation_copy",
      input.plan.target.copies,
      input.actualCopies,
    ),
    ...compareRows(
      "circulation_loan",
      input.plan.target.loans,
      input.actualLoans,
    ),
    ...compareRows(
      "circulation_fine",
      input.plan.target.fines,
      input.actualFines,
    ),
    ...compareRows(
      "circulation_fine_ledger_entry",
      input.plan.target.fineLedgerEntries,
      input.actualFineLedgerEntries,
    ),
  ].sort(
    (left, right) =>
      left.table.localeCompare(right.table) ||
      left.rowId.localeCompare(right.rowId),
  );

  const draft: ReconciliationReportWithoutHash = {
    schemaVersion: RECONCILIATION_SCHEMA,
    planSha256: input.plan.planSha256,
    observedAt: new Date(input.observedAt).toISOString(),
    target: {
      database: input.database,
      serverVersion: input.serverVersion,
    },
    status: mismatches.length === 0 ? "MATCH" : "MISMATCH",
    counts: {
      expectedCopies: input.plan.target.copies.length,
      actualCopies: input.actualCopies.length,
      expectedLoans: input.plan.target.loans.length,
      actualLoans: input.actualLoans.length,
      expectedFines: input.plan.target.fines.length,
      actualFines: input.actualFines.length,
      expectedFineLedgerEntries: input.plan.target.fineLedgerEntries.length,
      actualFineLedgerEntries: input.actualFineLedgerEntries.length,
    },
    mismatches,
    ...(input.application ? { application: input.application } : {}),
  };
  return { ...draft, reportSha256: sha256(draft) };
}

export function verifyReconciliationReport(
  value: unknown,
  expectedPlan?: MigrationPlan,
): ReconciliationReport {
  if (
    !isPlainObject(value) ||
    value.schemaVersion !== RECONCILIATION_SCHEMA ||
    typeof value.reportSha256 !== "string"
  ) {
    throw new TypeError(
      `Reconciliation report schemaVersion must be ${RECONCILIATION_SCHEMA}`,
    );
  }
  const { reportSha256, ...draft } = value;
  const computed = sha256(draft);
  if (computed !== reportSha256) {
    throw new TypeError(
      `Reconciliation integrity check failed: expected ${reportSha256}, computed ${computed}`,
    );
  }
  if (
    (value.status !== "MATCH" && value.status !== "MISMATCH") ||
    !Array.isArray(value.mismatches) ||
    typeof value.planSha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(value.planSha256)
  ) {
    throw new TypeError("Reconciliation report semantics are invalid");
  }
  if (
    (value.status === "MATCH" && value.mismatches.length !== 0) ||
    (value.status === "MISMATCH" && value.mismatches.length === 0)
  ) {
    throw new TypeError(
      "Reconciliation status does not agree with its mismatch rows",
    );
  }
  if (
    typeof value.observedAt !== "string" ||
    new Date(value.observedAt).toISOString() !== value.observedAt ||
    !isPlainObject(value.target) ||
    typeof value.target.database !== "string" ||
    value.target.database.length === 0 ||
    typeof value.target.serverVersion !== "string" ||
    value.target.serverVersion.length === 0 ||
    !isPlainObject(value.counts)
  ) {
    throw new TypeError("Reconciliation metadata is invalid");
  }
  const countNames = [
    "expectedCopies",
    "actualCopies",
    "expectedLoans",
    "actualLoans",
    "expectedFines",
    "actualFines",
    "expectedFineLedgerEntries",
    "actualFineLedgerEntries",
  ] as const;
  for (const name of countNames) {
    const count = value.counts[name];
    if (!Number.isSafeInteger(count) || (count as number) < 0) {
      throw new TypeError(`Reconciliation count ${name} is invalid`);
    }
  }
  if (
    value.status === "MATCH" &&
    (value.counts.expectedCopies !== value.counts.actualCopies ||
      value.counts.expectedLoans !== value.counts.actualLoans ||
      value.counts.expectedFines !== value.counts.actualFines ||
      value.counts.expectedFineLedgerEntries !==
        value.counts.actualFineLedgerEntries)
  ) {
    throw new TypeError("MATCH reconciliation counts differ");
  }

  const mismatchKeys = new Set<string>();
  for (const mismatch of value.mismatches) {
    if (
      !isPlainObject(mismatch) ||
      ![
        "circulation_copy",
        "circulation_loan",
        "circulation_fine",
        "circulation_fine_ledger_entry",
      ].includes(mismatch.table as string) ||
      typeof mismatch.rowId !== "string" ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
        mismatch.rowId,
      ) ||
      !["MISSING", "UNEXPECTED", "FIELD_MISMATCH"].includes(
        mismatch.kind as string,
      ) ||
      !Array.isArray(mismatch.differingFields) ||
      !mismatch.differingFields.every(
        (field) => typeof field === "string" && field.length > 0,
      ) ||
      (mismatch.expectedSha256 !== null &&
        (typeof mismatch.expectedSha256 !== "string" ||
          !/^[0-9a-f]{64}$/.test(mismatch.expectedSha256))) ||
      (mismatch.actualSha256 !== null &&
        (typeof mismatch.actualSha256 !== "string" ||
          !/^[0-9a-f]{64}$/.test(mismatch.actualSha256)))
    ) {
      throw new TypeError("Reconciliation mismatch row is invalid");
    }
    const key = `${String(mismatch.table)}:${mismatch.rowId}`;
    if (mismatchKeys.has(key)) {
      throw new TypeError("Reconciliation mismatch rows are duplicated");
    }
    mismatchKeys.add(key);
    if (
      (mismatch.kind === "MISSING" &&
        (mismatch.expectedSha256 === null ||
          mismatch.actualSha256 !== null ||
          mismatch.differingFields.length !== 0)) ||
      (mismatch.kind === "UNEXPECTED" &&
        (mismatch.expectedSha256 !== null ||
          mismatch.actualSha256 === null ||
          mismatch.differingFields.length !== 0)) ||
      (mismatch.kind === "FIELD_MISMATCH" &&
        (mismatch.expectedSha256 === null ||
          mismatch.actualSha256 === null ||
          mismatch.differingFields.length === 0))
    ) {
      throw new TypeError("Reconciliation mismatch shape is invalid");
    }
  }

  if (value.application !== undefined) {
    if (
      !isPlainObject(value.application) ||
      !["COMMITTED", "ROLLED_BACK"].includes(
        value.application.transactionOutcome as string,
      ) ||
      typeof value.application.transactionFinishedAt !== "string" ||
      new Date(value.application.transactionFinishedAt).toISOString() !==
        value.application.transactionFinishedAt ||
      new Date(value.application.transactionFinishedAt).valueOf() <
        new Date(value.observedAt).valueOf()
    ) {
      throw new TypeError("Reconciliation application evidence is invalid");
    }
    for (const name of [
      "insertedCopies",
      "insertedLoans",
      "insertedFines",
      "insertedFineLedgerEntries",
    ] as const) {
      const count = value.application[name];
      if (!Number.isSafeInteger(count) || (count as number) < 0) {
        throw new TypeError(`Application count ${name} is invalid`);
      }
    }
    if (
      value.application.transactionOutcome === "COMMITTED" &&
      value.status !== "MATCH"
    ) {
      throw new TypeError("A committed application report must be MATCH");
    }
    if (
      value.application.transactionOutcome === "ROLLED_BACK" &&
      value.status !== "MISMATCH"
    ) {
      throw new TypeError("A rolled-back application report must be MISMATCH");
    }
    if (
      (value.application.insertedCopies as number) >
        (value.counts.expectedCopies as number) ||
      (value.application.insertedLoans as number) >
        (value.counts.expectedLoans as number) ||
      (value.application.insertedFines as number) >
        (value.counts.expectedFines as number) ||
      (value.application.insertedFineLedgerEntries as number) >
        (value.counts.expectedFineLedgerEntries as number)
    ) {
      throw new TypeError(
        "Application insert counts exceed the supplied plan counts",
      );
    }
  }

  if (expectedPlan) {
    if (
      value.planSha256 !== expectedPlan.planSha256 ||
      value.counts.expectedCopies !== expectedPlan.target.copies.length ||
      value.counts.expectedLoans !== expectedPlan.target.loans.length ||
      value.counts.expectedFines !== expectedPlan.target.fines.length ||
      value.counts.expectedFineLedgerEntries !==
        expectedPlan.target.fineLedgerEntries.length
    ) {
      throw new TypeError(
        "Reconciliation report does not bind to the supplied plan",
      );
    }
  }
  return value as unknown as ReconciliationReport;
}
