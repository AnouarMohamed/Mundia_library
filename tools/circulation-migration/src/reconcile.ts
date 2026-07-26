import { sha256 } from "./canonical.js";
import {
  RECONCILIATION_SCHEMA,
  type MigrationPlan,
  type ReconciliationReport,
  type ReconciliationReportWithoutHash,
  type RowMismatch,
  type TargetCopy,
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
    },
    mismatches,
    ...(input.application ? { application: input.application } : {}),
  };
  return { ...draft, reportSha256: sha256(draft) };
}

export function verifyReconciliationReport(
  value: unknown,
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
  return value as unknown as ReconciliationReport;
}
