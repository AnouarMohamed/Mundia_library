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
  type TargetFine,
  type TargetFineLedgerEntry,
  type TargetLoan,
} from "./types.js";
import { isUuid, normalizeUuid, uuidV5 } from "./uuid.js";

export const DEFAULT_COPY_UUID_NAMESPACE =
  "8f3243f8-3d52-5aeb-97fc-66edbf3e3eb4";
export const HISTORICAL_FINANCE_ACTOR_FINGERPRINT = sha256(
  "mundiapolis:circulation-migration:historical-finance:v1",
);
export const HISTORICAL_FINE_REASON =
  "Imported legacy outstanding fine balance at circulation cutover";
const MAX_RENEWAL_COUNT = 100;
const MAX_FINE_MINOR = 1_000_000_000_000n;
const MAX_COPIES_PER_EDITION = 100_000;
const MAX_SOURCE_BOOKS = 1_000_000;
const MAX_SOURCE_LOANS = 2_000_000;
const MAX_TARGET_ROWS = 2_000_000;

export interface PlannerOptions {
  branchId: string;
  deterministicUuidNamespace?: string;
  preserveLegacyIdentifiersAcknowledged?: boolean;
  allowSyntheticHistoricalCopyAssignment?: boolean;
  fineMigrationPolicyAcknowledged?: boolean;
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
  if (
    value.books.length > MAX_SOURCE_BOOKS ||
    value.borrowRecords.length > MAX_SOURCE_LOANS
  ) {
    throw new TypeError(
      "Snapshot row count exceeds the bounded migration safety envelope",
    );
  }
  if (typeof value.capturedAt !== "string" || !isPlainObject(value.source)) {
    throw new TypeError("Snapshot capture metadata is missing");
  }
  if (
    typeof value.source.database !== "string" ||
    value.source.database.length === 0 ||
    value.source.database.length > 128 ||
    typeof value.source.serverVersion !== "string" ||
    value.source.serverVersion.length === 0 ||
    value.source.serverVersion.length > 128 ||
    value.source.contractVersion !==
      "legacy-circulation-source/pg18-v1" ||
    value.source.transactionIsolation !==
      "SERIALIZABLE_READ_ONLY_DEFERRABLE"
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

function parseFineMinor(value: unknown): bigint | null {
  if (value === null) return 0n;
  if (typeof value !== "string") return null;
  const match = /^(0|[1-9]\d*)(?:\.(\d{1,2}))?$/.exec(value);
  if (!match) return null;
  const fraction = (match[2] ?? "").padEnd(2, "0");
  const minor = BigInt(match[1]!) * 100n + BigInt(fraction || "0");
  return minor <= MAX_FINE_MINOR ? minor : null;
}

function archiveFieldsPresent(record: LegacyBorrowRecord): string[] {
  const present: string[] = [];
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
  if (!/^18(?:\.|\s|$)/.test(snapshot.source.serverVersion)) {
    addFinding(findings, {
      severity: "ERROR",
      code: "SOURCE_POSTGRES_VERSION_UNSUPPORTED",
      entityType: "SNAPSHOT",
      message:
        "Snapshot source metadata is below or outside the PostgreSQL 18 migration baseline.",
      remediation:
        "Capture the final snapshot from the reviewed PostgreSQL 18 restored source.",
    });
  }
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
  if (options.fineMigrationPolicyAcknowledged !== true) {
    addFinding(findings, {
      severity: "ERROR",
      code: "LEGACY_FINE_POLICY_NOT_ACKNOWLEDGED",
      entityType: "SNAPSHOT",
      message:
        "The MAD/current-outstanding-balance/null-means-no-fine migration policy was not explicitly acknowledged.",
      remediation:
        "Have the finance owner approve the recorded currency and balance semantics before creating a final plan.",
    });
  } else {
    addFinding(findings, {
      severity: "INFO",
      code: "LEGACY_FINE_POLICY_ACKNOWLEDGED",
      entityType: "SNAPSHOT",
      message:
        "Operator acknowledged MAD minor units, NULL as no fine, and current outstanding balance as an initial assessment.",
      remediation:
        "Retain the finance-owner approval with the independently authenticated plan hash.",
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
        new Date(bookUpdatedAt).valueOf() < new Date(bookCreatedAt).valueOf()) ||
      (bookCreatedAt &&
        new Date(bookCreatedAt).valueOf() > new Date(capturedAt).valueOf()) ||
      (bookUpdatedAt &&
        new Date(bookUpdatedAt).valueOf() > new Date(capturedAt).valueOf())
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
      source.totalCopies > MAX_COPIES_PER_EDITION ||
      !Number.isSafeInteger(source.availableCopies) ||
      source.availableCopies < 0 ||
      source.availableCopies > source.totalCopies
    ) {
      addFinding(findings, {
        severity: "ERROR",
        code: "BOOK_INVENTORY_INVALID",
        entityType: "BOOK",
        entityId: id,
        message: `Inventory is invalid or exceeds the ${MAX_COPIES_PER_EDITION}-copy per-edition migration bound: total=${String(
          source.totalCopies,
        )}, available=${String(source.availableCopies)}.`,
        remediation:
          "Reconcile total_copies and available_copies in the legacy system.",
      });
      continue;
    }
    books.set(id, { source, id });
  }
  const totalPlannedCopies = [...books.values()].reduce(
    (total, book) => total + book.source.totalCopies,
    0,
  );
  if (totalPlannedCopies > MAX_TARGET_ROWS) {
    throw new TypeError(
      `Snapshot would create ${totalPlannedCopies} copies, above the ${MAX_TARGET_ROWS}-row migration safety bound`,
    );
  }

  const duplicateLoanIds = new Set<string>();
  const seenLoanIds = new Set<string>();
  const openMemberEditions = new Map<string, string>();
  const sourceLoansByBook = new Map<string, LegacyBorrowRecord[]>();
  const sourceLoanById = new Map<string, LegacyBorrowRecord>();
  const fineMinorByLoanId = new Map<string, bigint>();

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
    if (
      !Number.isSafeInteger(source.renewalCount) ||
      source.renewalCount < 0 ||
      source.renewalCount > MAX_RENEWAL_COUNT
    ) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_RENEWAL_COUNT_INVALID",
        entityType: "LOAN",
        entityId: id,
        message: `Renewal count ${String(source.renewalCount)} is outside the target range 0-${MAX_RENEWAL_COUNT}.`,
        remediation:
          "Reconcile renewal_count before migration; it cannot be archived or truncated.",
      });
      continue;
    }
    if (source.status === "PENDING" && source.renewalCount !== 0) {
      addFinding(findings, {
        severity: "ERROR",
        code: "PENDING_LOAN_RENEWAL_INVALID",
        entityType: "LOAN",
        entityId: id,
        message: "A pending request cannot already contain renewals.",
        remediation: "Reconcile the legacy lifecycle and renewal state.",
      });
      continue;
    }
    const fineMinor = parseFineMinor(source.fineAmount);
    if (fineMinor === null) {
      addFinding(findings, {
        severity: "ERROR",
        code: "LOAN_FINE_AMOUNT_INVALID",
        entityType: "LOAN",
        entityId: id,
        message:
          "Fine amount is not a non-negative, exact two-decimal MAD amount within the target ledger range.",
        remediation:
          "Resolve the authoritative outstanding MAD balance; rounding, exponent notation, and truncation are forbidden.",
      });
      continue;
    }
    if (source.status === "PENDING" && fineMinor > 0n) {
      addFinding(findings, {
        severity: "ERROR",
        code: "FINE_ON_INELIGIBLE_LOAN",
        entityType: "LOAN",
        entityId: id,
        message: "A pending request has a nonzero fine balance.",
        remediation:
          "Resolve the lifecycle or fine ownership before migration.",
      });
      continue;
    }

    const bookId = normalizeUuid(source.bookId);
    const rows = sourceLoansByBook.get(bookId) ?? [];
    rows.push(source);
    sourceLoansByBook.set(bookId, rows);
    sourceLoanById.set(id, source);
    fineMinorByLoanId.set(id, fineMinor);

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
      if (
        !requestedAt ||
        new Date(requestedAt).valueOf() > new Date(capturedAt).valueOf()
      ) {
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
      const catalogCreatedAt = normalizeTimestamp(
        books.get(bookId)?.source.createdAt,
      );
      if (
        catalogCreatedAt &&
        new Date(requestedAt).valueOf() <
          new Date(catalogCreatedAt).valueOf()
      ) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LOAN_BEFORE_BOOK_ACQUISITION",
          entityType: "LOAN",
          entityId: id,
          message:
            "Loan checkout predates the catalog's recorded book acquisition.",
          remediation:
            "Recover authoritative catalog/loan timestamps before migration.",
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
        new Date(createdAt).valueOf() >
          new Date(requestedAt).valueOf() ||
        (updatedAt &&
          (new Date(updatedAt).valueOf() <
            new Date(requestedAt).valueOf() ||
            new Date(updatedAt).valueOf() >
              new Date(capturedAt).valueOf()))
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
      const archived = archiveFieldsPresent(source);
      if (archived.length > 0) {
        addFinding(findings, {
          severity: "ERROR",
          code: "LEGACY_METADATA_DESTINATION_UNRESOLVED",
          entityType: "LOAN",
          entityId: id,
          message: `Legacy metadata has no approved target destination: ${archived.join(", ")}.`,
          remediation:
            "Implement and approve an authenticated audit/records destination before circulation cutover; archive-only loss is forbidden.",
        });
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
          renewalCount: 0,
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
          version: 1 + source.renewalCount,
          renewalCount: source.renewalCount,
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
        (typeof source.returnDate === "string" &&
          source.returnDate > capturedAt.slice(0, 10)) ||
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
        version: 2 + source.renewalCount,
        renewalCount: source.renewalCount,
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

  const fines: TargetFine[] = [];
  const fineLedgerEntries: TargetFineLedgerEntry[] = [];
  for (const loan of targetLoans) {
    const source = sourceLoanById.get(loan.id);
    const fineMinor = fineMinorByLoanId.get(loan.id);
    if (!source || fineMinor === undefined || fineMinor === 0n) continue;

    const assessedAt = normalizeTimestamp(source.updatedAt);
    if (
      !assessedAt ||
      !loan.checkedOutAt ||
      !loan.dueAt ||
      new Date(assessedAt).valueOf() <= new Date(loan.dueAt).valueOf()
    ) {
      addFinding(findings, {
        severity: "ERROR",
        code: "FINE_ASSESSMENT_TIMESTAMP_INVALID",
        entityType: "LOAN",
        entityId: loan.id,
        message:
          "A nonzero fine has no explicit legacy updated_at after the mapped due timestamp.",
        remediation:
          "Recover the authoritative fine assessment timestamp; the migration will not invent or backdate one.",
      });
      continue;
    }

    const fineId = uuidV5(
      namespace,
      `${branchId}/${loan.id}/legacy-fine/MAD`,
    );
    const ledgerEntryId = uuidV5(
      namespace,
      `${branchId}/${fineId}/assessment/0`,
    );
    const amount = Number(fineMinor);
    fines.push({
      id: fineId,
      loanId: loan.id,
      memberId: loan.memberId,
      currency: "MAD",
      balanceMinor: amount,
      status: "OPEN",
      version: 0,
      createdAt: assessedAt,
      updatedAt: assessedAt,
    });
    fineLedgerEntries.push({
      id: ledgerEntryId,
      fineId,
      fineVersion: 0,
      entryType: "ASSESSMENT",
      deltaMinor: amount,
      actorFingerprint: HISTORICAL_FINANCE_ACTOR_FINGERPRINT,
      reason: HISTORICAL_FINE_REASON,
      externalReference: null,
      occurredAt: assessedAt,
      createdAt: assessedAt,
    });
  }
  fines.sort((a, b) => a.id.localeCompare(b.id));
  fineLedgerEntries.sort((a, b) => a.id.localeCompare(b.id));

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
        notes: record.notes,
        lastReminderSent: record.lastReminderSent,
        updatedBy: record.updatedBy,
      },
    }))
    .sort((a, b) => a.loanId.localeCompare(b.loanId));

  findings.sort(compareFindings);
  const hasErrors = findings.some((finding) => finding.severity === "ERROR");
  const renewalCountTotal = [...sourceLoanById.values()].reduce(
    (total, record) => total + BigInt(record.renewalCount),
    0n,
  );
  const sourceFineMinorValues = [...fineMinorByLoanId.values()];
  const fineBalanceMinorTotal = sourceFineMinorValues.reduce(
    (total, amount) => total + amount,
    0n,
  );
  const draft: MigrationPlanWithoutHash = {
    schemaVersion: PLAN_SCHEMA,
    mode: "DRY_RUN_PLAN",
    source: {
      capturedAt,
      database: snapshot.source.database,
      serverVersion: snapshot.source.serverVersion,
      sourceContractVersion: snapshot.source.contractVersion,
      snapshotSha256: sha256(snapshot),
      bookCount: snapshot.books.length,
      borrowRecordCount: snapshot.borrowRecords.length,
      renewalCountTotal: renewalCountTotal.toString(),
      nonzeroFineCount: sourceFineMinorValues.filter((amount) => amount > 0n)
        .length,
      fineBalanceMinorTotal: fineBalanceMinorTotal.toString(),
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
      legacyFineCurrency: "MAD",
      legacyNullFineAmount: "NO_FINE",
      legacyFineBalanceMeaning:
        "CURRENT_OUTSTANDING_AS_INITIAL_ASSESSMENT",
      fineAssessmentTimestamp: "LEGACY_UPDATED_AT",
      historicalFinanceActor: "MIGRATION_PRINCIPAL",
      historicalFinanceActorFingerprint:
        HISTORICAL_FINANCE_ACTOR_FINGERPRINT,
      timestampDateResolution: "UTC_END_OF_DAY",
      historicalOutboxEvents: "NONE",
    },
    target: {
      copies,
      loans: targetLoans,
      fines,
      fineLedgerEntries,
    },
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
        {
          name: "renewal_counts_preserved",
          passed:
            targetLoans.reduce(
              (total, loan) => total + BigInt(loan.renewalCount),
              0n,
            ) === renewalCountTotal,
          details: `${renewalCountTotal.toString()} total renewal(s) preserved`,
        },
        {
          name: "outstanding_fines_preserved",
          passed:
            fines.length ===
              sourceFineMinorValues.filter((amount) => amount > 0n).length &&
            fines.reduce(
              (total, fine) => total + BigInt(fine.balanceMinor),
              0n,
            ) === fineBalanceMinorTotal &&
            fineLedgerEntries.length === fines.length,
          details: `${fineBalanceMinorTotal.toString()} MAD minor unit(s) preserved in ${fines.length} immutable assessment(s)`,
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

function isCanonicalUuid(value: unknown): value is string {
  return isUuid(value) && value === value.toLowerCase();
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
      /^[0-9]+$/.test(plan.source.renewalCountTotal) &&
      Number.isSafeInteger(plan.source.nonzeroFineCount) &&
      plan.source.nonzeroFineCount >= 0 &&
      /^[0-9]+$/.test(plan.source.fineBalanceMinorTotal) &&
      isExactIsoTimestamp(plan.source.capturedAt),
    "source counts or capture timestamp are invalid",
  );
  assertPlan(
    plan.policy.bookIdMapsToEditionId === true &&
      plan.policy.userIdMapsToMemberId === true,
    "identifier preservation policy is absent",
  );
  assertPlan(
    plan.policy.historicalOutboxEvents === "NONE" &&
      (plan.policy.historicalCopyAssignment === "BLOCK_AMBIGUOUS" ||
        plan.policy.historicalCopyAssignment === "DETERMINISTIC_FEASIBLE") &&
      plan.policy.timestampDateResolution === "UTC_END_OF_DAY" &&
      plan.policy.legacyFineCurrency === "MAD" &&
      plan.policy.legacyNullFineAmount === "NO_FINE" &&
      plan.policy.legacyFineBalanceMeaning ===
        "CURRENT_OUTSTANDING_AS_INITIAL_ASSESSMENT" &&
      plan.policy.fineAssessmentTimestamp === "LEGACY_UPDATED_AT" &&
      plan.policy.historicalFinanceActor === "MIGRATION_PRINCIPAL" &&
      plan.policy.historicalFinanceActorFingerprint ===
        HISTORICAL_FINANCE_ACTOR_FINGERPRINT,
    "historical finance/outbox policy is invalid",
  );
  assertPlan(isCanonicalUuid(plan.policy.branchId), "branch id is invalid");
  assertPlan(
    isCanonicalUuid(plan.policy.deterministicUuidNamespace),
    "copy UUID namespace is invalid",
  );
  assertPlan(
    Array.isArray(plan.target?.copies) &&
      Array.isArray(plan.target?.loans) &&
      Array.isArray(plan.target?.fines) &&
      Array.isArray(plan.target?.fineLedgerEntries) &&
      Array.isArray(plan.findings) &&
      Array.isArray(plan.legacyLoanArchive) &&
      Array.isArray(plan.reconciliation?.checks) &&
      Array.isArray(plan.reconciliation?.editions),
    "required arrays are absent",
  );
  assertPlan(
    plan.target.copies.length <= MAX_TARGET_ROWS &&
      plan.target.loans.length <= MAX_TARGET_ROWS &&
      plan.target.fines.length <= MAX_TARGET_ROWS &&
      plan.target.fineLedgerEntries.length <= MAX_TARGET_ROWS,
    "target row count exceeds the migration safety envelope",
  );
  assertPlan(
    typeof plan.source.database === "string" &&
      plan.source.database.length > 0 &&
      plan.source.database.length <= 128 &&
      typeof plan.source.serverVersion === "string" &&
      plan.source.serverVersion.length > 0 &&
      plan.source.serverVersion.length <= 128 &&
      plan.source.sourceContractVersion ===
        "legacy-circulation-source/pg18-v1",
    "source identity metadata is invalid",
  );
  for (const finding of plan.findings) {
    assertPlan(
      isPlainObject(finding) &&
        ["ERROR", "WARNING", "INFO"].includes(
          finding.severity as string,
        ) &&
        typeof finding.code === "string" &&
        /^[A-Z][A-Z0-9_]{2,100}$/.test(finding.code) &&
        ["SNAPSHOT", "BOOK", "LOAN", "EDITION"].includes(
          finding.entityType as string,
        ) &&
        (finding.entityId === null ||
          isCanonicalUuid(finding.entityId)) &&
        typeof finding.message === "string" &&
        finding.message.length > 0 &&
        finding.message.length <= 1_000 &&
        typeof finding.remediation === "string" &&
        finding.remediation.length > 0 &&
        finding.remediation.length <= 1_000,
      "finding evidence is malformed",
    );
  }
  const expectedCheckNames = [
    "available_counter_matches_active_loans",
    "copy_count_equals_inventory_total",
    "no_error_findings",
    "all_legacy_loans_mapped",
    "outstanding_fines_preserved",
    "renewal_counts_preserved",
  ].sort();
  const actualCheckNames = plan.reconciliation.checks
    .map((check) => check.name)
    .sort();
  assertPlan(
    actualCheckNames.length === expectedCheckNames.length &&
      actualCheckNames.every(
        (name, index) => name === expectedCheckNames[index],
      ) &&
      plan.reconciliation.checks.every(
        (check) =>
          typeof check.passed === "boolean" &&
          typeof check.details === "string" &&
          check.details.length > 0 &&
          check.details.length <= 1_000,
      ),
    "reconciliation checks are malformed or incomplete",
  );

  const copiesById = new Map<string, TargetCopy>();
  const copiesByBarcode = new Set<string>();
  const copiesByEdition = new Map<string, TargetCopy[]>();
  for (const copy of plan.target.copies) {
    assertPlan(
      isCanonicalUuid(copy.id),
      `copy ${String(copy.id)} id is invalid`,
    );
    assertPlan(
      isCanonicalUuid(copy.editionId),
      `copy ${copy.id} edition id is invalid`,
    );
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
        isExactIsoTimestamp(copy.updatedAt) &&
        new Date(copy.updatedAt).valueOf() >=
          new Date(copy.createdAt).valueOf(),
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
      isCanonicalUuid(edition.editionId) &&
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
  const loansById = new Map<string, TargetLoan>();
  const activeCopyIds = new Set<string>();
  const openMemberEditions = new Set<string>();
  const intervalsByCopy = new Map<
    string,
    Array<{ loanId: string; start: number; end: number }>
  >();
  for (const loan of plan.target.loans) {
    assertPlan(
      isCanonicalUuid(loan.id) &&
        isCanonicalUuid(loan.memberId) &&
        isCanonicalUuid(loan.editionId),
      `loan ${String(loan.id)} identifiers are invalid`,
    );
    assertPlan(!loanIds.has(loan.id), `loan ${loan.id} is duplicated`);
    loanIds.add(loan.id);
    loansById.set(loan.id, loan);
    assertPlan(
      isExactIsoTimestamp(loan.requestedAt) &&
        isExactIsoTimestamp(loan.createdAt) &&
        isExactIsoTimestamp(loan.updatedAt) &&
        new Date(loan.createdAt).valueOf() <=
          new Date(loan.requestedAt).valueOf() &&
        new Date(loan.updatedAt).valueOf() >=
          new Date(loan.requestedAt).valueOf() &&
        new Date(loan.updatedAt).valueOf() >=
          new Date(loan.createdAt).valueOf() &&
        Number.isSafeInteger(loan.renewalCount) &&
        loan.renewalCount >= 0 &&
        loan.renewalCount <= MAX_RENEWAL_COUNT,
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
          loan.version === 0 &&
          loan.renewalCount === 0,
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
        isCanonicalUuid(loan.copyId) &&
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
          loan.version === 1 + loan.renewalCount &&
          copy?.status === "ON_LOAN" &&
          !activeCopyIds.has(loan.copyId),
        `active loan ${loan.id} state or copy exclusivity is invalid`,
      );
      activeCopyIds.add(loan.copyId);
    } else {
      assertPlan(
          isExactIsoTimestamp(loan.returnedAt) &&
          loan.rejectedAt === null &&
          loan.version === 2 + loan.renewalCount &&
          new Date(loan.returnedAt).valueOf() >=
            new Date(loan.checkedOutAt).valueOf(),
        `returned loan ${loan.id} state is invalid`,
      );
    }
    const copyIntervals = intervalsByCopy.get(loan.copyId) ?? [];
    copyIntervals.push({
      loanId: loan.id,
      start: new Date(loan.checkedOutAt).valueOf(),
      end:
        loan.status === "ACTIVE"
          ? Number.POSITIVE_INFINITY
          : new Date(loan.returnedAt!).valueOf(),
    });
    intervalsByCopy.set(loan.copyId, copyIntervals);
  }

  for (const [copyId, intervals] of intervalsByCopy) {
    intervals.sort(
      (left, right) =>
        left.start - right.start || left.loanId.localeCompare(right.loanId),
    );
    for (let index = 1; index < intervals.length; index += 1) {
      assertPlan(
        intervals[index - 1]!.end <= intervals[index]!.start,
        `copy ${copyId} has overlapping historical loan intervals`,
      );
    }
  }

  for (const copy of plan.target.copies) {
    assertPlan(
      (copy.status === "ON_LOAN") === activeCopyIds.has(copy.id),
      `copy ${copy.id} current status differs from active-loan ownership`,
    );
    if (copy.status === "ON_LOAN") {
      const loan = plan.target.loans.find(
        (candidate) =>
          candidate.status === "ACTIVE" && candidate.copyId === copy.id,
      );
      assertPlan(
        loan !== undefined &&
          copy.updatedAt === loan.checkedOutAt &&
          new Date(copy.createdAt).valueOf() <=
            new Date(loan.checkedOutAt).valueOf(),
        `copy ${copy.id} timestamps differ from its active loan`,
      );
    }
  }

  const fineIds = new Set<string>();
  const fineLoanIds = new Set<string>();
  const finesById = new Map<string, TargetFine>();
  for (const fine of plan.target.fines) {
    const loan = loansById.get(fine.loanId);
    assertPlan(
      isCanonicalUuid(fine.id) &&
        loan !== undefined &&
        (loan.status === "ACTIVE" || loan.status === "RETURNED") &&
        fine.memberId === loan.memberId,
      `fine ${String(fine.id)} has invalid loan/member identity`,
    );
    assertPlan(
      !fineIds.has(fine.id) && !fineLoanIds.has(fine.loanId),
      `fine ${fine.id} is duplicated for its id or loan`,
    );
    assertPlan(
      fine.id ===
        uuidV5(
          plan.policy.deterministicUuidNamespace,
          `${plan.policy.branchId}/${fine.loanId}/legacy-fine/MAD`,
        ),
      `fine ${fine.id} is not the deterministic legacy fine id`,
    );
    assertPlan(
      fine.currency === "MAD" &&
        Number.isSafeInteger(fine.balanceMinor) &&
        fine.balanceMinor > 0 &&
        BigInt(fine.balanceMinor) <= MAX_FINE_MINOR &&
        fine.status === "OPEN" &&
        fine.version === 0 &&
        isExactIsoTimestamp(fine.createdAt) &&
        fine.updatedAt === fine.createdAt &&
        loan.dueAt !== null &&
        new Date(fine.createdAt).valueOf() > new Date(loan.dueAt).valueOf(),
      `fine ${fine.id} state, amount, or timestamp is invalid`,
    );
    fineIds.add(fine.id);
    fineLoanIds.add(fine.loanId);
    finesById.set(fine.id, fine);
  }

  const ledgerIds = new Set<string>();
  const ledgerFineIds = new Set<string>();
  for (const entry of plan.target.fineLedgerEntries) {
    const fine = finesById.get(entry.fineId);
    assertPlan(
      isCanonicalUuid(entry.id) &&
        fine !== undefined &&
        !ledgerIds.has(entry.id) &&
        !ledgerFineIds.has(entry.fineId),
      `fine ledger entry ${String(entry.id)} is invalid or duplicated`,
    );
    assertPlan(
      entry.id ===
        uuidV5(
          plan.policy.deterministicUuidNamespace,
          `${plan.policy.branchId}/${entry.fineId}/assessment/0`,
        ),
      `fine ledger entry ${entry.id} is not deterministic`,
    );
    assertPlan(
      entry.fineVersion === 0 &&
        entry.entryType === "ASSESSMENT" &&
        entry.deltaMinor === fine.balanceMinor &&
        entry.actorFingerprint ===
          plan.policy.historicalFinanceActorFingerprint &&
        entry.reason === HISTORICAL_FINE_REASON &&
        entry.externalReference === null &&
        entry.occurredAt === fine.createdAt &&
        entry.createdAt === entry.occurredAt,
      `fine ledger entry ${entry.id} does not exactly represent its assessment`,
    );
    ledgerIds.add(entry.id);
    ledgerFineIds.add(entry.fineId);
  }
  assertPlan(
    ledgerFineIds.size === fineIds.size,
    "every migrated fine must have exactly one assessment ledger entry",
  );

  const archivedLoanIds = new Set<string>();
  for (const archived of plan.legacyLoanArchive) {
    assertPlan(
      isCanonicalUuid(archived.loanId) &&
        !archivedLoanIds.has(archived.loanId) &&
        /^[0-9a-f]{64}$/.test(archived.sourceRowSha256),
      `legacy archive ${String(archived.loanId)} is invalid or duplicated`,
    );
    const fields = archived.fieldsNotRepresentedInTarget;
    assertPlan(
      isPlainObject(fields) &&
        Object.keys(fields).sort().join(",") ===
          "borrowedBy,lastReminderSent,notes,returnedBy,updatedBy" &&
        Object.values(fields).every(
          (field) => field === null || typeof field === "string",
        ),
      `legacy archive ${archived.loanId} fields are malformed`,
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
    assertPlan(
      plan.target.loans.reduce(
        (total, loan) => total + BigInt(loan.renewalCount),
        0n,
      ) === BigInt(plan.source.renewalCountTotal),
      "READY plan does not preserve the source renewal total",
    );
    assertPlan(
      plan.target.fines.length === plan.source.nonzeroFineCount &&
        plan.target.fines.reduce(
          (total, fine) => total + BigInt(fine.balanceMinor),
          0n,
        ) === BigInt(plan.source.fineBalanceMinorTotal),
      "READY plan does not preserve every nonzero legacy fine balance",
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
