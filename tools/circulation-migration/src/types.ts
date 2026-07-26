export const SNAPSHOT_SCHEMA = "legacy-circulation-snapshot/v1" as const;
export const PLAN_SCHEMA = "circulation-migration-plan/v1" as const;
export const RECONCILIATION_SCHEMA = "circulation-reconciliation/v1" as const;

export type LegacyBorrowStatus = "PENDING" | "BORROWED" | "RETURNED";
export type TargetCopyStatus = "AVAILABLE" | "ON_LOAN";
export type TargetLoanStatus = "REQUESTED" | "ACTIVE" | "RETURNED";
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
  shelfLocation: null;
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
  rejectedAt: null;
  version: number;
  createdAt: string;
  updatedAt: string;
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
    fineAmount: string | null;
    notes: string | null;
    renewalCount: number;
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
    snapshotSha256: string;
    bookCount: number;
    borrowRecordCount: number;
  };
  policy: {
    branchId: string;
    deterministicUuidNamespace: string;
    bookIdMapsToEditionId: true;
    userIdMapsToMemberId: true;
    historicalCopyAssignment: "BLOCK_AMBIGUOUS" | "DETERMINISTIC_FEASIBLE";
    unsupportedOperationalFields: "BLOCK" | "ARCHIVE_WITH_WARNING";
    timestampDateResolution: "UTC_END_OF_DAY";
    historicalOutboxEvents: "NONE";
  };
  target: {
    copies: TargetCopy[];
    loans: TargetLoan[];
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
  table: "circulation_copy" | "circulation_loan";
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
  };
  mismatches: RowMismatch[];
  application?: {
    transactionOutcome: "COMMITTED" | "ROLLED_BACK";
    insertedCopies: number;
    insertedLoans: number;
  };
}

export interface ReconciliationReport extends ReconciliationReportWithoutHash {
  reportSha256: string;
}
