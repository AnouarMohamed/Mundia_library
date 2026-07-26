export const SNAPSHOT_SCHEMA = "legacy-circulation-snapshot/v2" as const;
export const PLAN_SCHEMA = "circulation-migration-plan/v2" as const;
export const RECONCILIATION_SCHEMA = "circulation-reconciliation/v2" as const;

export type LegacyBorrowStatus = "PENDING" | "BORROWED" | "RETURNED";
export type TargetCopyStatus =
  | "AVAILABLE"
  | "ON_LOAN"
  | "RESERVED"
  | "LOST"
  | "DAMAGED"
  | "WITHDRAWN";
export type TargetLoanStatus =
  | "REQUESTED"
  | "ACTIVE"
  | "RETURNED"
  | "REJECTED"
  | "CANCELLED";
export type FindingSeverity = "ERROR" | "WARNING" | "INFO";

export interface LegacyBook {
  id: string;
  totalCopies: number;
  availableCopies: number;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface LegacyBorrowRecord {
  id: string;
  userId: string;
  bookId: string;
  borrowDate: string;
  dueDate: string | null;
  returnDate: string | null;
  status: LegacyBorrowStatus;
  borrowedBy: string | null;
  returnedBy: string | null;
  fineAmount: string | null;
  notes: string | null;
  renewalCount: number;
  lastReminderSent: string | null;
  updatedAt: string | null;
  updatedBy: string | null;
  createdAt: string | null;
}

export interface LegacySnapshot {
  schemaVersion: typeof SNAPSHOT_SCHEMA;
  capturedAt: string;
  source: {
    database: string;
    serverVersion: string;
    contractVersion: "legacy-circulation-source/pg18-v1";
    transactionIsolation: "SERIALIZABLE_READ_ONLY_DEFERRABLE";
  };
  books: LegacyBook[];
  borrowRecords: LegacyBorrowRecord[];
}

export interface TargetCopy {
  id: string;
  editionId: string;
  branchId: string;
  barcode: string;
  status: TargetCopyStatus;
  shelfLocation: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TargetLoan {
  id: string;
  memberId: string;
  editionId: string;
  copyId: string | null;
  status: TargetLoanStatus;
  requestedAt: string;
  checkedOutAt: string | null;
  dueAt: string | null;
  returnedAt: string | null;
  rejectedAt: string | null;
  version: number;
  renewalCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TargetFine {
  id: string;
  loanId: string;
  memberId: string;
  currency: string;
  balanceMinor: number;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TargetFineLedgerEntry {
  id: string;
  fineId: string;
  fineVersion: number;
  entryType: string;
  deltaMinor: number;
  actorFingerprint: string;
  reason: string | null;
  externalReference: string | null;
  occurredAt: string;
  createdAt: string;
}

export interface Finding {
  severity: FindingSeverity;
  code: string;
  entityType: "SNAPSHOT" | "BOOK" | "LOAN" | "EDITION";
  entityId: string | null;
  message: string;
  remediation: string;
}

export interface LegacyLoanArchive {
  loanId: string;
  sourceRowSha256: string;
  fieldsNotRepresentedInTarget: {
    borrowedBy: string | null;
    returnedBy: string | null;
    notes: string | null;
    lastReminderSent: string | null;
    updatedBy: string | null;
  };
}

export interface EditionReconciliation {
  editionId: string;
  sourceTotalCopies: number;
  sourceAvailableCopies: number;
  sourceOpenLoans: number;
  plannedCopies: number;
  plannedAvailableCopies: number;
  plannedActiveLoans: number;
  plannedRequestedLoans: number;
  plannedReturnedLoans: number;
}

export interface PlanCheck {
  name: string;
  passed: boolean;
  details: string;
}

export interface MigrationPlanWithoutHash {
  schemaVersion: typeof PLAN_SCHEMA;
  mode: "DRY_RUN_PLAN";
  source: {
    capturedAt: string;
    database: string;
    serverVersion: string;
    sourceContractVersion: "legacy-circulation-source/pg18-v1";
    snapshotSha256: string;
    bookCount: number;
    borrowRecordCount: number;
    renewalCountTotal: string;
    nonzeroFineCount: number;
    fineBalanceMinorTotal: string;
  };
  policy: {
    branchId: string;
    deterministicUuidNamespace: string;
    bookIdMapsToEditionId: true;
    userIdMapsToMemberId: true;
    historicalCopyAssignment: "BLOCK_AMBIGUOUS" | "DETERMINISTIC_FEASIBLE";
    legacyFineCurrency: "MAD";
    legacyNullFineAmount: "NO_FINE";
    legacyFineBalanceMeaning: "CURRENT_OUTSTANDING_AS_INITIAL_ASSESSMENT";
    fineAssessmentTimestamp: "LEGACY_UPDATED_AT";
    historicalFinanceActor: "MIGRATION_PRINCIPAL";
    historicalFinanceActorFingerprint: string;
    timestampDateResolution: "UTC_END_OF_DAY";
    historicalOutboxEvents: "NONE";
  };
  target: {
    copies: TargetCopy[];
    loans: TargetLoan[];
    fines: TargetFine[];
    fineLedgerEntries: TargetFineLedgerEntry[];
  };
  legacyLoanArchive: LegacyLoanArchive[];
  findings: Finding[];
  reconciliation: {
    status: "READY" | "BLOCKED";
    checks: PlanCheck[];
    editions: EditionReconciliation[];
  };
}

export interface MigrationPlan extends MigrationPlanWithoutHash {
  planSha256: string;
}

export interface RowMismatch {
  table:
    | "circulation_copy"
    | "circulation_loan"
    | "circulation_fine"
    | "circulation_fine_ledger_entry";
  rowId: string;
  kind: "MISSING" | "UNEXPECTED" | "FIELD_MISMATCH";
  differingFields: string[];
  expectedSha256: string | null;
  actualSha256: string | null;
}

export interface ReconciliationReportWithoutHash {
  schemaVersion: typeof RECONCILIATION_SCHEMA;
  planSha256: string;
  observedAt: string;
  target: {
    database: string;
    serverVersion: string;
  };
  status: "MATCH" | "MISMATCH";
  counts: {
    expectedCopies: number;
    actualCopies: number;
    expectedLoans: number;
    actualLoans: number;
    expectedFines: number;
    actualFines: number;
    expectedFineLedgerEntries: number;
    actualFineLedgerEntries: number;
  };
  mismatches: RowMismatch[];
  application?: {
    transactionOutcome: "COMMITTED" | "ROLLED_BACK";
    transactionFinishedAt: string;
    insertedCopies: number;
    insertedLoans: number;
    insertedFines: number;
    insertedFineLedgerEntries: number;
  };
}

export interface ReconciliationReport extends ReconciliationReportWithoutHash {
  reportSha256: string;
}
