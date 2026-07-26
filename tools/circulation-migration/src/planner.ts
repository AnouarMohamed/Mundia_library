import { sha256 } from "./canonical.js";
import {
  PLAN_SCHEMA,
  SNAPSHOT_SCHEMA,
  type EditionReconciliation,
  type Finding,
  type LegacyBook,
  type LegacyBorrowRecord,
  type LegacySnapshot,
  type MigrationPlan,
  type MigrationPlanWithoutHash,
  type TargetCopy,
  type TargetLoan,
} from "./types.js";
import { isUuid, normalizeUuid, uuidV5 } from "./uuid.js";

export const DEFAULT_COPY_UUID_NAMESPACE =
  "8f3243f8-3d52-5aeb-97fc-66edbf3e3eb4";

export interface PlannerOptions {
  branchId: string;
  deterministicUuidNamespace?: string;
  preserveLegacyIdentifiersAcknowledged?: boolean;
  allowSyntheticHistoricalCopyAssignment?: boolean;
  allowArchivedUnsupportedOperationalFields?: boolean;
}

interface NormalizedBook {
  source: LegacyBook;
  id: string;
}

interface LoanInterval {
  source: LegacyBorrowRecord;
  loan: TargetLoan;
  start: number;
  end: number;
  active: boolean;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.getPrototypeOf(value) === Object.prototype
  );
}

export function parseSnapshot(value: unknown): LegacySnapshot {
  if (!isPlainObject(value)) throw new TypeError("Snapshot must be an object");
  if (value.schemaVersion !== SNAPSHOT_SCHEMA) {
    throw new TypeError(`Snapshot schemaVersion must be ${SNAPSHOT_SCHEMA}`);
  }
  if (!Array.isArray(value.books) || !Array.isArray(value.borrowRecords)) {
    throw new TypeError("Snapshot books and borrowRecords must be arrays");
  }
  if (typeof value.capturedAt !== "string" || !isPlainObject(value.source)) {
    throw new TypeError("Snapshot capture metadata is missing");
  }
  if (
    typeof value.source.database !== "string" ||
    typeof value.source.serverVersion !== "string"
  ) {
    throw new TypeError("Snapshot source metadata is invalid");
  }
  return value as unknown as LegacySnapshot;
}

function normalizeTimestamp(value: unknown): string | null {
  if (typeof value !== "string" || !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) {
    return null;
  }
  const parsed = new Date(value);
  return Number.isFinite(parsed.valueOf()) ? parsed.toISOString() : null;
}

function endOfUtcDay(value: unknown): string | null {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return null;
  }
  const timestamp = `${value}T23:59:59.999Z`;
  const parsed = new Date(timestamp);
  if (!Number.isFinite(parsed.valueOf())) return null;
  if (parsed.toISOString() !== timestamp) return null;
  return timestamp;
}

function compareFindings(left: Finding, right: Finding): number {
  const severityOrder = { ERROR: 0, WARNING: 1, INFO: 2 };
  return (
    severityOrder[left.severity] - severityOrder[right.severity] ||
    left.code.localeCompare(right.code) ||
    (left.entityId ?? "").localeCompare(right.entityId ?? "") ||
    left.message.localeCompare(right.message)
  );
}

function barcode(branchId: string, bookId: string, index: number): string {
  return `LEGACY-${branchId.slice(0, 8).toUpperCase()}-${bookId
    .replaceAll("-", "")
    .toUpperCase()}-${String(index).padStart(6, "0")}`;
}

function addFinding(
  findings: Finding[],
  finding: Omit<Finding, "entityId"> & { entityId?: string | null },
): void {
  findings.push({ ...finding, entityId: finding.entityId ?? null });
}

function operationalFieldsPresent(record: LegacyBorrowRecord): string[] {
  const present: string[] = [];
  const fine = Number(record.fineAmount ?? "0");
  if (Number.isFinite(fine) && fine !== 0) present.push("fineAmount");
  if (record.renewalCount !== 0) present.push("renewalCount");
  return present;
}

function archiveFieldsPresent(record: LegacyBorrowRecord): string[] {
  const present = operationalFieldsPresent(record);
  if (record.borrowedBy) present.push("borrowedBy");
  if (record.returnedBy) present.push("returnedBy");
  if (record.notes) present.push("notes");
  if (record.lastReminderSent) present.push("lastReminderSent");
  if (record.updatedBy) present.push("updatedBy");
  return present;
}

export function buildPlan(
  rawSnapshot: unknown,
  options: PlannerOptions,
): MigrationPlan {
  const snapshot = parseSnapshot(rawSnapshot);
  const branchId = normalizeUuid(options.branchId);
  const namespace = normalizeUuid(
    options.deterministicUuidNamespace ?? DEFAULT_COPY_UUID_NAMESPACE,
  );
  const capturedAt = normalizeTimestamp(snapshot.capturedAt);
  if (!capturedAt) {
    throw new TypeError(
      "snapshot.capturedAt must be an ISO-8601 timestamp with an explicit offset",
    );
  }

  const findings: Finding[] = [];
  if (options.preserveLegacyIdentifiersAcknowledged !== true) {
    addFinding(findings, {
      severity: "ERROR",
      code: "IDENTIFIER_MAPPING_NOT_ACKNOWLEDGED",
      entityType: "SNAPSHOT",
      message:
        "The plan maps legacy book UUIDs to edition UUIDs and user UUIDs to member UUIDs.",
      remediation:
        "Verify catalog and membership identifier preservation, then acknowledge the preserve-legacy-uuids policy.",
    });
  } else {
    addFinding(findings, {
      severity: "INFO",
      code: "IDENTIFIER_MAPPING_ACKNOWLEDGED",
      entityType: "SNAPSHOT",
      message:
        "Operator acknowledged book→edition and user→member UUID preservation.",
      remediation:
        "Retain independent catalog and membership reconciliation evidence.",
    });
  }
  const books = new Map<string, NormalizedBook>();
  const seenBookIds = new Set<string>();

  for (const source of snapshot.books) {
    if (!isUuid(source.id)) {
      addFinding(findings, {
        severity: "ERROR",
        code: "BOOK_ID_INVALID",
        entityType: "BOOK",
        message: "Book id is not a canonical UUID.",
        remediation: "Correct the legacy book identifier before migration.",
      });
      continue;
    }
    const id = normalizeUuid(source.id);
    if (seenBookIds.has(id)) {
      addFinding(findings, {
        severity: "ERROR",
        code: "BOOK_ID_DUPLICATE",
        entityType: "BOOK",
        entityId: id,
        message: "The snapshot contains the book more than once.",
        remediation: "Produce a source snapshot with unique primary keys.",
      });
      continue;
    }
    seenBookIds.add(id);
    const bookCreatedAt = normalizeTimestamp(source.createdAt);
    const bookUpdatedAt = normalizeTimestamp(source.updatedAt);
    if (
      (source.createdAt !== null && !bookCreatedAt) ||
      (source.updatedAt !== null && !bookUpdatedAt) ||
      (bookCreatedAt &&
        bookUpdatedAt &&
        new Date(bookUpdatedAt).valueOf() < new Date(bookCreatedAt).valueOf())
    ) {
      addFinding(findings, {
        severity: "ERROR",
        code: "BOOK_TIMESTAMP_INVALID",
        entityType: "BOOK",
        entityId: id,
        message: "Book creation/update timestamps are invalid or out of order.",
        remediation: "Recover authoritative catalog timestamps.",
      });
    }
    if (
      !Number.isSafeInteger(source.totalCopies) ||
      source.totalCopies < 0 ||
      !Number.isSafeInteger(source.availableCopies) ||
      source.availableCopies < 0 ||
      source.availableCopies > source.totalCopies
    ) {
      addFinding(findings, {
        severity: "ERROR",
        code: "BOOK_INVENTORY_INVALID",
        entityType: "BOOK",
        entityId: id,
        message: `Inventory is invalid: total=${String(
          source.totalCopies,
        )}, available=${String(source.availableCopies)}.`,
        remediation:
          "Reconcile total_copies and available_copies in the legacy system.",
      });
      continue;
    }
    books.set(id, { source, id });
  }

  const duplicateLoanIds = new Set<string>();
  const seenLoanIds = new Set<string>();
  const openMemberEditions = new Map<string, string>();
  const sourceLoansByBook = new Map<string, LegacyBorrowRecord[]>();

  for (const source of snapshot.borrowRecords) {
    if (!isUuid(source.id)) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_ID_INVALID",
        entityType: "LOAN",
        message: "Borrow record id is not a canonical UUID.",
        remediation: "Correct the legacy borrow identifier before migration.",
      });
      continue;
    }
    const id = normalizeUuid(source.id);
    if (seenLoanIds.has(id)) {
      duplicateLoanIds.add(id);
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_ID_DUPLICATE",
        entityType: "LOAN",
        entityId: id,
        message: "The snapshot contains the borrow record more than once.",
        remediation: "Produce a source snapshot with unique primary keys.",
      });
      continue;
    }
    seenLoanIds.add(id);

    if (!isUuid(source.bookId) || !books.has(normalizeUuid(source.bookId))) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_BOOK_MISSING",
        entityType: "LOAN",
        entityId: id,
        message: "Borrow record refers to a missing or invalid legacy book.",
        remediation:
          "Restore the referenced book or explicitly archive the loan.",
      });
      continue;
    }
    if (!isUuid(source.userId)) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_MEMBER_ID_INVALID",
        entityType: "LOAN",
        entityId: id,
        message: "Borrow record user_id is not a UUID.",
        remediation: "Map the legacy user to an institutional membership UUID.",
      });
      continue;
    }
    if (!["PENDING", "BORROWED", "RETURNED"].includes(source.status)) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_STATUS_INVALID",
        entityType: "LOAN",
        entityId: id,
        message: `Unsupported borrow status ${String(source.status)}.`,
        remediation: "Resolve the status before creating the final snapshot.",
      });
      continue;
    }

    const bookId = normalizeUuid(source.bookId);
    const rows = sourceLoansByBook.get(bookId) ?? [];
    rows.push(source);
    sourceLoansByBook.set(bookId, rows);

    if (source.status === "PENDING" || source.status === "BORROWED") {
      const key = `${normalizeUuid(source.userId)}:${bookId}`;
      const previous = openMemberEditions.get(key);
      if (previous) {
        addFinding(findings, {
          severity: "ERROR",
          code: "OPEN_LOAN_DUPLICATE",
          entityType: "LOAN",
          entityId: id,
          message: `Open loan conflicts with ${previous} for the same member and edition.`,
          remediation:
            "Resolve duplicate open requests/loans before migration.",
        });
      } else {
        openMemberEditions.set(key, id);
      }
    }
  }

  const copies: TargetCopy[] = [];
  for (const book of [...books.values()].sort((a, b) =>
    a.id.localeCompare(b.id),
  )) {
    const createdAt = normalizeTimestamp(book.source.createdAt) ?? capturedAt;
    const updatedAt = normalizeTimestamp(book.source.updatedAt) ?? createdAt;
    for (let index = 1; index <= book.source.totalCopies; index += 1) {
      copies.push({
        id: uuidV5(namespace, `${branchId}/${book.id}/${index}`),
        editionId: book.id,
        branchId,
        barcode: barcode(branchId, book.id, index),
        status: "AVAILABLE",
        shelfLocation: null,
        version: 0,
        createdAt,
        updatedAt,
      });
    }
  }

  const copyByEdition = new Map<string, TargetCopy[]>();
  for (const copy of copies) {
    const editionCopies = copyByEdition.get(copy.editionId) ?? [];
    editionCopies.push(copy);
    copyByEdition.set(copy.editionId, editionCopies);
  }

  const targetLoans: TargetLoan[] = [];
  for (const [bookId, sourceRows] of [...sourceLoansByBook.entries()].sort(
    ([left], [right]) => left.localeCompare(right),
  )) {
    const intervals: LoanInterval[] = [];
    const editionCopies = copyByEdition.get(bookId) ?? [];
    const hasPhysicalHistory = sourceRows.some(
      (row) => row.status !== "PENDING",
    );
    if (hasPhysicalHistory && editionCopies.length > 1) {
      addFinding(findings, {
        severity: options.allowSyntheticHistoricalCopyAssignment
          ? "WARNING"
          : "ERROR",
        code: "HISTORICAL_COPY_IDENTITY_AMBIGUOUS",
        entityType: "EDITION",
        entityId: bookId,
        message: `${sourceRows.filter((row) => row.status !== "PENDING").length} physical loan(s) have no legacy copy identifier across ${editionCopies.length} copies.`,
        remediation: options.allowSyntheticHistoricalCopyAssignment
          ? "Retain this evidence and have a librarian approve the deterministic assignment before cutover."
          : "Supply an authoritative copy mapping, or re-plan with the explicit synthetic-assignment policy after librarian approval.",
      });
    }
    if (hasPhysicalHistory) {
      addFinding(findings, {
        severity: "WARNING",
        code: "REQUEST_AND_CHECKOUT_TIMESTAMP_COLLAPSED",
        entityType: "EDITION",
        entityId: bookId,
        message:
          "Legacy borrow_date represents both requested_at and checked_out_at for physical loans.",
        remediation:
          "Accept the documented loss of request-to-approval latency or supply authoritative audit timestamps.",
      });
    }

    for (const source of [...sourceRows].sort((a, b) =>
      a.id.localeCompare(b.id),
    )) {
      const id = normalizeUuid(source.id);
      if (duplicateLoanIds.has(id)) continue;
      const requestedAt = normalizeTimestamp(source.borrowDate);
      if (!requestedAt) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_BORROW_TIMESTAMP_INVALID",
          entityType: "LOAN",
          entityId: id,
          message:
            "borrow_date is not an ISO-8601 timestamp with an explicit offset.",
          remediation: "Recover an authoritative request/checkout timestamp.",
        });
        continue;
      }

      const memberId = normalizeUuid(source.userId);
      const createdAt = normalizeTimestamp(source.createdAt) ?? requestedAt;
      const updatedAt = normalizeTimestamp(source.updatedAt);
      if (
        (source.createdAt !== null &&
          normalizeTimestamp(source.createdAt) === null) ||
        (source.updatedAt !== null && updatedAt === null) ||
        (updatedAt &&
          new Date(updatedAt).valueOf() < new Date(createdAt).valueOf())
      ) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_AUDIT_TIMESTAMP_INVALID",
          entityType: "LOAN",
          entityId: id,
          message:
            "Loan creation/update timestamps are invalid or out of order.",
          remediation: "Recover authoritative loan audit timestamps.",
        });
      }
      const fineAmount = Number(source.fineAmount ?? "0");
      if (
        (source.fineAmount !== null &&
          !/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(source.fineAmount)) ||
        !Number.isFinite(fineAmount) ||
        fineAmount < 0 ||
        !Number.isSafeInteger(source.renewalCount) ||
        source.renewalCount < 0
      ) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_OPERATIONAL_DATA_INVALID",
          entityType: "LOAN",
          entityId: id,
          message: "Fine amount or renewal count is invalid.",
          remediation:
            "Reconcile fine_amount and renewal_count before migration.",
        });
      }
      const operational = operationalFieldsPresent(source);
      if (operational.length > 0) {
        addFinding(findings, {
          severity: options.allowArchivedUnsupportedOperationalFields
            ? "WARNING"
            : "ERROR",
          code: "OPERATIONAL_FIELDS_NOT_IN_TARGET",
          entityType: "LOAN",
          entityId: id,
          message: `Target schema cannot represent: ${operational.join(", ")}.`,
          remediation: options.allowArchivedUnsupportedOperationalFields
            ? "Confirm the machine-readable legacy archive is an accepted retention boundary."
            : "Extend the target domain or explicitly approve archival before planning again.",
        });
      } else {
        const archived = archiveFieldsPresent(source);
        if (archived.length > 0) {
          addFinding(findings, {
            severity: "WARNING",
            code: "LEGACY_METADATA_ARCHIVED",
            entityType: "LOAN",
            entityId: id,
            message: `Non-domain metadata is retained only in evidence: ${archived.join(", ")}.`,
            remediation:
              "Retain the signed plan evidence according to institutional records policy.",
          });
        }
      }

      if (source.status === "PENDING") {
        if (source.dueDate !== null || source.returnDate !== null) {
          addFinding(findings, {
            severity: "ERROR",
            code: "PENDING_LOAN_STATE_INVALID",
            entityType: "LOAN",
            entityId: id,
            message: "Pending loan has a due date or return date.",
            remediation: "Reconcile the legacy lifecycle state.",
          });
          continue;
        }
        targetLoans.push({
          id,
          memberId,
          editionId: bookId,
          copyId: null,
          status: "REQUESTED",
          requestedAt,
          checkedOutAt: null,
          dueAt: null,
          returnedAt: null,
          rejectedAt: null,
          version: 0,
          createdAt,
          updatedAt: updatedAt ?? createdAt,
        });
        continue;
      }

      const dueAt = endOfUtcDay(source.dueDate);
      if (
        !dueAt ||
        new Date(dueAt).valueOf() <= new Date(requestedAt).valueOf()
      ) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_DUE_DATE_INVALID",
          entityType: "LOAN",
          entityId: id,
          message:
            "Physical loan has no due date after its checkout timestamp.",
          remediation: "Recover or correct the authoritative due date.",
        });
        continue;
      }
      if (editionCopies.length === 0) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_WITHOUT_PHYSICAL_COPY",
          entityType: "LOAN",
          entityId: id,
          message: "Physical loan belongs to a book with zero owned copies.",
          remediation: "Correct total_copies or archive the invalid loan.",
        });
        continue;
      }

      if (source.status === "BORROWED") {
        if (source.returnDate !== null) {
          addFinding(findings, {
            severity: "ERROR",
            code: "ACTIVE_LOAN_RETURN_DATE_PRESENT",
            entityType: "LOAN",
            entityId: id,
            message: "Borrowed loan already has a return date.",
            remediation: "Mark it RETURNED or remove the invalid date.",
          });
          continue;
        }
        const loan: TargetLoan = {
          id,
          memberId,
          editionId: bookId,
          copyId: null,
          status: "ACTIVE",
          requestedAt,
          checkedOutAt: requestedAt,
          dueAt,
          returnedAt: null,
          rejectedAt: null,
          version: 1,
          createdAt,
          updatedAt: updatedAt ?? requestedAt,
        };
        intervals.push({
          source,
          loan,
          start: new Date(requestedAt).valueOf(),
          end: Number.POSITIVE_INFINITY,
          active: true,
        });
        continue;
      }

      const returnedAt = endOfUtcDay(source.returnDate);
      if (
        !returnedAt ||
        new Date(returnedAt).valueOf() < new Date(requestedAt).valueOf()
      ) {
        addFinding(findings, {
          severity: "ERROR",
          code: "RETURN_TIMESTAMP_INVALID",
          entityType: "LOAN",
          entityId: id,
          message: "Returned loan has no return date at or after checkout.",
          remediation: "Recover or correct the authoritative return date.",
        });
        continue;
      }
      const loan: TargetLoan = {
        id,
        memberId,
        editionId: bookId,
        copyId: null,
        status: "RETURNED",
        requestedAt,
        checkedOutAt: requestedAt,
        dueAt,
        returnedAt,
        rejectedAt: null,
        version: 2,
        createdAt,
        updatedAt: updatedAt ?? returnedAt,
      };
      intervals.push({
        source,
        loan,
        start: new Date(requestedAt).valueOf(),
        end: new Date(returnedAt).valueOf(),
        active: false,
      });
    }

    const copyAvailableAt = editionCopies.map(() => Number.NEGATIVE_INFINITY);
    for (const interval of intervals.sort(
      (a, b) => a.start - b.start || a.loan.id.localeCompare(b.loan.id),
    )) {
      const copyIndex = copyAvailableAt.findIndex(
        (availableAt) => availableAt <= interval.start,
      );
      if (copyIndex < 0) {
        addFinding(findings, {
          severity: "ERROR",
          code: "COPY_TIMELINE_OVER_CAPACITY",
          entityType: "LOAN",
          entityId: interval.loan.id,
          message:
            "No physical copy is available for this historical loan interval.",
          remediation:
            "Resolve overlapping dates/copy counts or provide an authoritative physical-copy mapping.",
        });
        continue;
      }
      const assignedCopy = editionCopies[copyIndex]!;
      interval.loan.copyId = assignedCopy.id;
      copyAvailableAt[copyIndex] = interval.end;
      if (interval.active) {
        assignedCopy.status = "ON_LOAN";
        assignedCopy.version = 1;
        assignedCopy.updatedAt = interval.loan.checkedOutAt!;
      }
      targetLoans.push(interval.loan);
    }
  }

  targetLoans.sort((a, b) => a.id.localeCompare(b.id));
  copies.sort((a, b) => a.id.localeCompare(b.id));

  const editions: EditionReconciliation[] = [...books.values()]
    .sort((a, b) => a.id.localeCompare(b.id))
    .map(({ id, source }) => {
      const plannedCopies = copies.filter((copy) => copy.editionId === id);
      const loans = targetLoans.filter((loan) => loan.editionId === id);
      const sourceOpenLoans = (sourceLoansByBook.get(id) ?? []).filter(
        (loan) => loan.status === "BORROWED",
      ).length;
      const plannedAvailable = plannedCopies.filter(
        (copy) => copy.status === "AVAILABLE",
      ).length;
      if (source.availableCopies !== source.totalCopies - sourceOpenLoans) {
        addFinding(findings, {
          severity: "ERROR",
          code: "AVAILABLE_COUNTER_DRIFT",
          entityType: "EDITION",
          entityId: id,
          message: `Legacy available_copies=${source.availableCopies}, but total_copies - active_loans=${source.totalCopies - sourceOpenLoans}.`,
          remediation:
            "Reconcile inventory atomically in the legacy database before final freeze.",
        });
      }
      return {
        editionId: id,
        sourceTotalCopies: source.totalCopies,
        sourceAvailableCopies: source.availableCopies,
        sourceOpenLoans,
        plannedCopies: plannedCopies.length,
        plannedAvailableCopies: plannedAvailable,
        plannedActiveLoans: loans.filter((loan) => loan.status === "ACTIVE")
          .length,
        plannedRequestedLoans: loans.filter(
          (loan) => loan.status === "REQUESTED",
        ).length,
        plannedReturnedLoans: loans.filter((loan) => loan.status === "RETURNED")
          .length,
      };
    });

  const archive = snapshot.borrowRecords
    .filter((record) => isUuid(record.id))
    .map((record) => ({
      loanId: normalizeUuid(record.id),
      sourceRowSha256: sha256(record),
      fieldsNotRepresentedInTarget: {
        borrowedBy: record.borrowedBy,
        returnedBy: record.returnedBy,
        fineAmount: record.fineAmount,
        notes: record.notes,
        renewalCount: record.renewalCount,
        lastReminderSent: record.lastReminderSent,
        updatedBy: record.updatedBy,
      },
    }))
    .sort((a, b) => a.loanId.localeCompare(b.loanId));

  findings.sort(compareFindings);
  const hasErrors = findings.some((finding) => finding.severity === "ERROR");
  const draft: MigrationPlanWithoutHash = {
    schemaVersion: PLAN_SCHEMA,
    mode: "DRY_RUN_PLAN",
    source: {
      capturedAt,
      database: snapshot.source.database,
      serverVersion: snapshot.source.serverVersion,
      snapshotSha256: sha256(snapshot),
      bookCount: snapshot.books.length,
      borrowRecordCount: snapshot.borrowRecords.length,
    },
    policy: {
      branchId,
      deterministicUuidNamespace: namespace,
      bookIdMapsToEditionId: true,
      userIdMapsToMemberId: true,
      historicalCopyAssignment:
        options.allowSyntheticHistoricalCopyAssignment === true
          ? "DETERMINISTIC_FEASIBLE"
          : "BLOCK_AMBIGUOUS",
      unsupportedOperationalFields:
        options.allowArchivedUnsupportedOperationalFields === true
          ? "ARCHIVE_WITH_WARNING"
          : "BLOCK",
      timestampDateResolution: "UTC_END_OF_DAY",
      historicalOutboxEvents: "NONE",
    },
    target: { copies, loans: targetLoans },
    legacyLoanArchive: archive,
    findings,
    reconciliation: {
      status: hasErrors ? "BLOCKED" : "READY",
      checks: [
        {
          name: "no_error_findings",
          passed: !hasErrors,
          details: `${findings.filter((finding) => finding.severity === "ERROR").length} error finding(s)`,
        },
        {
          name: "all_legacy_loans_mapped",
          passed: targetLoans.length === snapshot.borrowRecords.length,
          details: `${targetLoans.length}/${snapshot.borrowRecords.length} loan(s) mapped`,
        },
        {
          name: "copy_count_equals_inventory_total",
          passed:
            copies.length ===
            [...books.values()].reduce(
              (total, book) => total + book.source.totalCopies,
              0,
            ),
          details: `${copies.length} target copy row(s)`,
        },
        {
          name: "available_counter_matches_active_loans",
          passed: !findings.some(
            (finding) => finding.code === "AVAILABLE_COUNTER_DRIFT",
          ),
          details:
            "Legacy counters are checked against active loans per edition.",
        },
      ],
      editions,
    },
  };

  return { ...draft, planSha256: sha256(draft) };
}

export function verifyPlan(value: unknown): MigrationPlan {
  if (!isPlainObject(value) || value.schemaVersion !== PLAN_SCHEMA) {
    throw new TypeError(`Plan schemaVersion must be ${PLAN_SCHEMA}`);
  }
  if (typeof value.planSha256 !== "string") {
    throw new TypeError("Plan planSha256 is missing");
  }
  const { planSha256, ...draft } = value;
  const computed = sha256(draft);
  if (computed !== planSha256) {
    throw new TypeError(
      `Plan integrity check failed: expected ${planSha256}, computed ${computed}`,
    );
  }
  const plan = value as unknown as MigrationPlan;
  validatePlanSemantics(plan);
  return plan;
}

function assertPlan(condition: unknown, message: string): asserts condition {
  if (!condition)
    throw new TypeError(`Plan semantic validation failed: ${message}`);
}

function isExactIsoTimestamp(value: unknown): value is string {
  return typeof value === "string" && normalizeTimestamp(value) === value;
}

function validatePlanSemantics(plan: MigrationPlan): void {
  assertPlan(plan.mode === "DRY_RUN_PLAN", "mode must be DRY_RUN_PLAN");
  assertPlan(
    /^[0-9a-f]{64}$/.test(plan.source.snapshotSha256),
    "source snapshot checksum is invalid",
  );
  assertPlan(
    Number.isSafeInteger(plan.source.bookCount) &&
      plan.source.bookCount >= 0 &&
      Number.isSafeInteger(plan.source.borrowRecordCount) &&
      plan.source.borrowRecordCount >= 0 &&
      isExactIsoTimestamp(plan.source.capturedAt),
    "source counts or capture timestamp are invalid",
  );
  assertPlan(
    plan.policy.bookIdMapsToEditionId === true &&
      plan.policy.userIdMapsToMemberId === true,
    "identifier preservation policy is absent",
  );
  assertPlan(
    plan.policy.historicalOutboxEvents === "NONE",
    "historical outbox policy must be NONE",
  );
  assertPlan(isUuid(plan.policy.branchId), "branch id is invalid");
  assertPlan(
    isUuid(plan.policy.deterministicUuidNamespace),
    "copy UUID namespace is invalid",
  );
  assertPlan(
    Array.isArray(plan.target?.copies) &&
      Array.isArray(plan.target?.loans) &&
      Array.isArray(plan.findings) &&
      Array.isArray(plan.legacyLoanArchive) &&
      Array.isArray(plan.reconciliation?.checks) &&
      Array.isArray(plan.reconciliation?.editions),
    "required arrays are absent",
  );

  const copiesById = new Map<string, TargetCopy>();
  const copiesByBarcode = new Set<string>();
  const copiesByEdition = new Map<string, TargetCopy[]>();
  for (const copy of plan.target.copies) {
    assertPlan(isUuid(copy.id), `copy ${String(copy.id)} id is invalid`);
    assertPlan(isUuid(copy.editionId), `copy ${copy.id} edition id is invalid`);
    assertPlan(
      copy.branchId === plan.policy.branchId,
      `copy ${copy.id} branch differs from the plan branch`,
    );
    assertPlan(
      copy.status === "AVAILABLE" || copy.status === "ON_LOAN",
      `copy ${copy.id} status is invalid`,
    );
    assertPlan(
      copy.shelfLocation === null,
      `copy ${copy.id} has an unplanned shelf location`,
    );
    assertPlan(
      copy.version === (copy.status === "ON_LOAN" ? 1 : 0),
      `copy ${copy.id} version does not match migration state`,
    );
    assertPlan(
      isExactIsoTimestamp(copy.createdAt) &&
        isExactIsoTimestamp(copy.updatedAt),
      `copy ${copy.id} timestamps are invalid`,
    );
    assertPlan(!copiesById.has(copy.id), `copy ${copy.id} is duplicated`);
    assertPlan(
      !copiesByBarcode.has(copy.barcode),
      `copy barcode ${copy.barcode} is duplicated`,
    );
    copiesById.set(copy.id, copy);
    copiesByBarcode.add(copy.barcode);
    const rows = copiesByEdition.get(copy.editionId) ?? [];
    rows.push(copy);
    copiesByEdition.set(copy.editionId, rows);
  }

  const reconciliationEditionIds = new Set<string>();
  for (const edition of plan.reconciliation.editions) {
    assertPlan(
      isUuid(edition.editionId) &&
        !reconciliationEditionIds.has(edition.editionId),
      `edition reconciliation ${String(edition.editionId)} is invalid or duplicated`,
    );
    reconciliationEditionIds.add(edition.editionId);
    const editionCopies = copiesByEdition.get(edition.editionId) ?? [];
    const editionLoans = plan.target.loans.filter(
      (loan) => loan.editionId === edition.editionId,
    );
    assertPlan(
      Number.isSafeInteger(edition.sourceTotalCopies) &&
        edition.sourceTotalCopies >= 0 &&
        Number.isSafeInteger(edition.sourceAvailableCopies) &&
        edition.sourceAvailableCopies >= 0 &&
        edition.sourceAvailableCopies <= edition.sourceTotalCopies &&
        Number.isSafeInteger(edition.sourceOpenLoans) &&
        edition.sourceOpenLoans >= 0,
      `edition ${edition.editionId} source inventory is invalid`,
    );
    assertPlan(
      edition.sourceTotalCopies === editionCopies.length &&
        edition.plannedCopies === editionCopies.length &&
        edition.plannedAvailableCopies ===
          editionCopies.filter((copy) => copy.status === "AVAILABLE").length,
      `edition ${edition.editionId} copy reconciliation differs from target rows`,
    );
    assertPlan(
      edition.plannedActiveLoans ===
        editionLoans.filter((loan) => loan.status === "ACTIVE").length &&
        edition.plannedRequestedLoans ===
          editionLoans.filter((loan) => loan.status === "REQUESTED").length &&
        edition.plannedReturnedLoans ===
          editionLoans.filter((loan) => loan.status === "RETURNED").length,
      `edition ${edition.editionId} loan reconciliation differs from target rows`,
    );
    if (plan.reconciliation.status === "READY") {
      assertPlan(
        edition.sourceAvailableCopies === edition.plannedAvailableCopies &&
          edition.sourceOpenLoans === edition.plannedActiveLoans,
        `READY edition ${edition.editionId} differs from source inventory`,
      );
    }
  }
  if (plan.reconciliation.status === "READY") {
    assertPlan(
      reconciliationEditionIds.size === plan.source.bookCount,
      "source book count differs from edition reconciliation",
    );
  }
  for (const copy of plan.target.copies) {
    assertPlan(
      reconciliationEditionIds.has(copy.editionId),
      `copy ${copy.id} edition is absent from reconciliation`,
    );
  }

  for (const [editionId, copies] of copiesByEdition) {
    for (let index = 1; index <= copies.length; index += 1) {
      const expectedBarcode = barcode(plan.policy.branchId, editionId, index);
      const expectedId = uuidV5(
        plan.policy.deterministicUuidNamespace,
        `${plan.policy.branchId}/${editionId}/${index}`,
      );
      const copy = copies.find(
        (candidate) => candidate.barcode === expectedBarcode,
      );
      assertPlan(
        copy?.id === expectedId,
        `edition ${editionId} deterministic copy ordinal ${index} is absent`,
      );
    }
  }

  const loanIds = new Set<string>();
  const activeCopyIds = new Set<string>();
  const openMemberEditions = new Set<string>();
  for (const loan of plan.target.loans) {
    assertPlan(
      isUuid(loan.id) && isUuid(loan.memberId) && isUuid(loan.editionId),
      `loan ${String(loan.id)} identifiers are invalid`,
    );
    assertPlan(!loanIds.has(loan.id), `loan ${loan.id} is duplicated`);
    loanIds.add(loan.id);
    assertPlan(
      isExactIsoTimestamp(loan.requestedAt) &&
        isExactIsoTimestamp(loan.createdAt) &&
        isExactIsoTimestamp(loan.updatedAt),
      `loan ${loan.id} timestamps are invalid`,
    );
    const openKey = `${loan.memberId}:${loan.editionId}`;
    if (loan.status === "REQUESTED" || loan.status === "ACTIVE") {
      assertPlan(
        !openMemberEditions.has(openKey),
        `loan ${loan.id} duplicates an open member/edition`,
      );
      openMemberEditions.add(openKey);
    }
    if (loan.status === "REQUESTED") {
      assertPlan(
        loan.copyId === null &&
          loan.checkedOutAt === null &&
          loan.dueAt === null &&
          loan.returnedAt === null &&
          loan.rejectedAt === null &&
          loan.version === 0,
        `requested loan ${loan.id} state is invalid`,
      );
      continue;
    }
    assertPlan(
      loan.status === "ACTIVE" || loan.status === "RETURNED",
      `loan ${loan.id} status is invalid`,
    );
    assertPlan(
      loan.copyId !== null &&
        isUuid(loan.copyId) &&
        isExactIsoTimestamp(loan.checkedOutAt) &&
        isExactIsoTimestamp(loan.dueAt),
      `physical loan ${loan.id} has incomplete copy/timestamp state`,
    );
    const copy = copiesById.get(loan.copyId);
    assertPlan(
      copy?.editionId === loan.editionId,
      `loan ${loan.id} copy belongs to a different edition`,
    );
    assertPlan(
      new Date(loan.dueAt).valueOf() > new Date(loan.checkedOutAt).valueOf(),
      `loan ${loan.id} due timestamp is not after checkout`,
    );
    if (loan.status === "ACTIVE") {
      assertPlan(
        loan.returnedAt === null &&
          loan.rejectedAt === null &&
          loan.version === 1 &&
          copy?.status === "ON_LOAN" &&
          !activeCopyIds.has(loan.copyId),
        `active loan ${loan.id} state or copy exclusivity is invalid`,
      );
      activeCopyIds.add(loan.copyId);
    } else {
      assertPlan(
        isExactIsoTimestamp(loan.returnedAt) &&
          loan.rejectedAt === null &&
          loan.version === 2 &&
          new Date(loan.returnedAt).valueOf() >=
            new Date(loan.checkedOutAt).valueOf(),
        `returned loan ${loan.id} state is invalid`,
      );
    }
  }

  for (const copy of plan.target.copies) {
    assertPlan(
      (copy.status === "ON_LOAN") === activeCopyIds.has(copy.id),
      `copy ${copy.id} current status differs from active-loan ownership`,
    );
  }

  const archivedLoanIds = new Set<string>();
  for (const archived of plan.legacyLoanArchive) {
    assertPlan(
      isUuid(archived.loanId) &&
        !archivedLoanIds.has(archived.loanId) &&
        /^[0-9a-f]{64}$/.test(archived.sourceRowSha256),
      `legacy archive ${String(archived.loanId)} is invalid or duplicated`,
    );
    archivedLoanIds.add(archived.loanId);
  }

  if (plan.reconciliation.status === "READY") {
    assertPlan(
      !plan.findings.some((finding) => finding.severity === "ERROR"),
      "READY plan contains error findings",
    );
    assertPlan(
      plan.reconciliation.checks.every((check) => check.passed === true),
      "READY plan contains a failed reconciliation check",
    );
    assertPlan(
      plan.target.loans.length === plan.source.borrowRecordCount,
      "READY plan does not map every source loan",
    );
    assertPlan(
      plan.legacyLoanArchive.length === plan.source.borrowRecordCount,
      "READY plan does not archive every source loan",
    );
    for (const loanId of loanIds) {
      assertPlan(
        archivedLoanIds.has(loanId),
        `READY plan loan ${loanId} is absent from the source archive`,
      );
    }
  } else {
    assertPlan(
      plan.reconciliation.status === "BLOCKED",
      "reconciliation status is invalid",
    );
  }
}
