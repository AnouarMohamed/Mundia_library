import { createHash } from "node:crypto";

const IMPORT_NAMESPACE = "2f943f97-73a7-522b-b29f-844c1f0621e4";
const MAX_BATCH_SIZE = 100;
const MAX_REQUEST_BYTES = 120_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface LegacyMembershipRecord {
  id: string;
  email: string;
  fullName: string;
  universityId: number;
  status: "PENDING" | "APPROVED" | "REJECTED";
  role: "USER" | "ADMIN" | "SUPER_ADMIN";
  universityCard: string;
  maxActiveLoans: number;
  currentActiveLoans: number;
  hasUnpaidOverdueFines: boolean;
  createdAt: Date | null;
  updatedAt: Date | null;
}

export interface LegacyMembershipImportItem {
  memberId: string;
  email: string;
  fullName: string;
  universityId: number;
  status: LegacyMembershipRecord["status"];
  role: LegacyMembershipRecord["role"];
  maxActiveLoans: number;
  currentActiveLoans: number;
  hasUnpaidOverdueFines: boolean;
  createdAt: string;
  updatedAt: string;
  evidenceReferenceSha256: string;
  contentSha256: string;
}

export interface LegacyMembershipImportBatch {
  importId: string;
  sourceRevision: string;
  items: LegacyMembershipImportItem[];
}

export interface LegacyMembershipPlan {
  status: "READY" | "BLOCKED";
  sourceRevision: string;
  sourceCount: number;
  batches: LegacyMembershipImportBatch[];
  findings: Array<{ memberId: string; fields: string[] }>;
}

export function planLegacyMembershipBackfill(
  records: LegacyMembershipRecord[],
  batchSize = MAX_BATCH_SIZE,
): LegacyMembershipPlan {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
    throw new Error("Membership migration batch size must be between 1 and 100");
  }
  const findings: LegacyMembershipPlan["findings"] = [];
  const memberIds = new Set<string>();
  const emails = new Map<string, string>();
  const universityIds = new Map<number, string>();
  const evidenceDigests = new Map<string, string>();
  const items: LegacyMembershipImportItem[] = [];
  for (const record of records.toSorted((left, right) => left.id.localeCompare(right.id))) {
    const invalid = invalidFields(record);
    const normalizedEmail = record.email.trim().toLowerCase();
    const evidenceDigest = digest(record.universityCard.trim());
    duplicate(memberIds, record.id.toLowerCase(), "memberId", invalid);
    duplicateMap(emails, normalizedEmail, record.id, "email", invalid);
    duplicateMap(universityIds, record.universityId, record.id, "universityId", invalid);
    duplicateMap(evidenceDigests, evidenceDigest, record.id, "evidenceReference", invalid);
    if (invalid.length > 0) {
      findings.push({ memberId: record.id, fields: [...new Set(invalid)].sort() });
      continue;
    }
    const item = normalize(record, evidenceDigest);
    items.push({ ...item, contentSha256: itemSha256(item) });
  }
  if (records.length === 0) findings.push({ memberId: "source", fields: ["emptyMembership"] });
  if (!records.some((record) => record.status === "APPROVED" && ["ADMIN", "SUPER_ADMIN"].includes(record.role))) {
    findings.push({ memberId: "source", fields: ["missingApprovedAdministrator"] });
  }
  const sourceRevision = digest({
    version: "legacy-membership-source-v1",
    records: records.toSorted((left, right) => left.id.localeCompare(right.id)).map(sourceRecord),
  });
  const batches: LegacyMembershipImportBatch[] = [];
  if (findings.length === 0) {
    let chunk: LegacyMembershipImportItem[] = [];
    for (const item of items) {
      const candidate = [...chunk, item];
      if (chunk.length > 0 && (candidate.length > batchSize || requestBytes(sourceRevision, candidate) > MAX_REQUEST_BYTES)) {
        batches.push(batch(sourceRevision, chunk));
        chunk = [];
      }
      if (requestBytes(sourceRevision, [item]) > MAX_REQUEST_BYTES) {
        findings.push({ memberId: item.memberId, fields: ["requestBodyTooLarge"] });
      } else {
        chunk.push(item);
      }
    }
    if (chunk.length > 0) batches.push(batch(sourceRevision, chunk));
  }
  if (findings.length > 0) batches.length = 0;
  return {
    status: findings.length === 0 ? "READY" : "BLOCKED",
    sourceRevision,
    sourceCount: records.length,
    batches,
    findings,
  };
}

export async function applyLegacyMembershipBatch(input: {
  baseUrl: string;
  bearerToken: string;
  batch: LegacyMembershipImportBatch;
  fetcher?: typeof fetch;
}) {
  const baseUrl = new URL(input.baseUrl);
  if (baseUrl.protocol !== "https:" && baseUrl.hostname !== "localhost") {
    throw new Error("MEMBERSHIP_SERVICE_URL must use HTTPS outside localhost");
  }
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new Error("MEMBERSHIP_SERVICE_URL must be a clean service origin");
  }
  if (input.bearerToken.trim().length < 20) throw new Error("MEMBERSHIP_IMPORT_BEARER_TOKEN is invalid");
  const endpoint = new URL(`/api/v1/members/legacy-imports/${input.batch.importId}`, baseUrl);
  const authorization = `Bearer ${input.bearerToken}`;
  const fetcher = input.fetcher ?? fetch;
  const applied = await fetcher(endpoint, {
    method: "PUT",
    headers: { authorization, "content-type": "application/json" },
    body: JSON.stringify({ sourceRevision: input.batch.sourceRevision, items: input.batch.items }),
    redirect: "error",
    signal: AbortSignal.timeout(15_000),
  });
  if (!applied.ok) throw new Error(`Membership import failed with HTTP ${applied.status}`);
  const result = await safeReceipt(applied);
  const reconciled = await fetcher(endpoint, {
    headers: { authorization }, redirect: "error", signal: AbortSignal.timeout(10_000),
  });
  if (!reconciled.ok) throw new Error(`Membership reconciliation failed with HTTP ${reconciled.status}`);
  const evidence = await safeReceipt(reconciled);
  if (
    result.manifestSha256 !== evidence.manifestSha256 ||
    result.sourceRevision !== input.batch.sourceRevision ||
    evidence.sourceRevision !== input.batch.sourceRevision ||
    evidence.memberCount !== input.batch.items.length ||
    evidence.quarantinedEvidenceCount !== input.batch.items.length
  ) throw new Error("Membership reconciliation evidence does not match the applied batch");
  return evidence;
}

function normalize(
  record: LegacyMembershipRecord,
  evidenceReferenceSha256: string,
): Omit<LegacyMembershipImportItem, "contentSha256"> {
  return {
    memberId: record.id.toLowerCase(),
    email: record.email.trim().toLowerCase(),
    fullName: record.fullName.trim(),
    universityId: record.universityId,
    status: record.status,
    role: record.role,
    maxActiveLoans: record.maxActiveLoans,
    currentActiveLoans: record.currentActiveLoans,
    hasUnpaidOverdueFines: record.hasUnpaidOverdueFines,
    createdAt: requireDate(record.createdAt).toISOString(),
    updatedAt: requireDate(record.updatedAt).toISOString(),
    evidenceReferenceSha256,
  };
}

function invalidFields(record: LegacyMembershipRecord): string[] {
  const invalid: string[] = [];
  if (!UUID.test(record.id)) invalid.push("memberId");
  const email = record.email.trim();
  if (email.length < 3 || email.length > 320 || email.split("@").length !== 2 ||
    [...email].some((character) => character.charCodeAt(0) < 33 || character.charCodeAt(0) > 126)) invalid.push("email");
  if (!record.fullName.trim() || record.fullName.trim().length > 200 || hasControl(record.fullName.trim())) invalid.push("fullName");
  if (!Number.isSafeInteger(record.universityId) || record.universityId <= 0) invalid.push("universityId");
  if (!record.universityCard.trim() || record.universityCard.trim().length > 4_096 || hasControl(record.universityCard.trim())) invalid.push("universityCard");
  if (!Number.isSafeInteger(record.maxActiveLoans) || record.maxActiveLoans < 0 || record.maxActiveLoans > 100) invalid.push("maxActiveLoans");
  if (!Number.isSafeInteger(record.currentActiveLoans) || record.currentActiveLoans < 0 || record.currentActiveLoans > record.maxActiveLoans) invalid.push("currentActiveLoans");
  if (!record.createdAt || !Number.isFinite(record.createdAt.valueOf())) invalid.push("createdAt");
  if (!record.updatedAt || !Number.isFinite(record.updatedAt.valueOf())) invalid.push("updatedAt");
  if (record.createdAt && record.updatedAt && record.updatedAt < record.createdAt) invalid.push("timestampOrder");
  return invalid;
}

function itemSha256(item: Omit<LegacyMembershipImportItem, "contentSha256">): string {
  return digest(lengthPrefixed([
    "membership-legacy-item-v1", item.memberId, item.email, item.fullName,
    String(item.universityId), item.status, item.role, String(item.maxActiveLoans),
    String(item.currentActiveLoans), String(item.hasUnpaidOverdueFines),
    String(new Date(item.createdAt).valueOf()), String(new Date(item.updatedAt).valueOf()),
    item.evidenceReferenceSha256,
  ]));
}

function batch(sourceRevision: string, items: LegacyMembershipImportItem[]): LegacyMembershipImportBatch {
  return {
    importId: uuidV5(`${sourceRevision}:${items.map((item) => item.memberId).join(",")}`, IMPORT_NAMESPACE),
    sourceRevision,
    items,
  };
}

function sourceRecord(record: LegacyMembershipRecord) {
  return { ...record, createdAt: record.createdAt?.toISOString() ?? null, updatedAt: record.updatedAt?.toISOString() ?? null };
}
function duplicate(values: Set<string>, value: string, field: string, findings: string[]) {
  if (values.has(value)) findings.push(`${field}Duplicate`); else values.add(value);
}
function duplicateMap<T>(values: Map<T, string>, value: T, owner: string, field: string, findings: string[]) {
  const existing = values.get(value);
  if (existing) findings.push(`${field}Duplicate:${existing}`); else values.set(value, owner);
}
function hasControl(value: string): boolean {
  return [...value].some((character) => character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127);
}
function requireDate(value: Date | null): Date { if (!value) throw new Error("Timestamp is required"); return value; }
function requestBytes(sourceRevision: string, items: LegacyMembershipImportItem[]): number {
  return Buffer.byteLength(JSON.stringify({ sourceRevision, items }), "utf8");
}
function lengthPrefixed(values: string[]): string {
  return values[0]! + values.slice(1).map((value) => `\u001f${value.length}:${value}`).join("");
}
function digest(value: unknown): string {
  const input = typeof value === "string" ? value : JSON.stringify(value);
  return createHash("sha256").update(input).digest("hex");
}
function uuidV5(name: string, namespace: string): string {
  const namespaceBytes = Buffer.from(namespace.replaceAll("-", ""), "hex");
  const bytes = createHash("sha1").update(namespaceBytes).update(name, "utf8").digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
async function safeReceipt(response: Response): Promise<Record<string, unknown>> {
  const value: unknown = await response.json();
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Membership receipt is invalid");
  const receipt = value as Record<string, unknown>;
  if (
    typeof receipt.manifestSha256 !== "string" || !/^[0-9a-f]{64}$/.test(receipt.manifestSha256) ||
    typeof receipt.sourceRevision !== "string" || !/^[0-9a-f]{64}$/.test(receipt.sourceRevision) ||
    !Number.isSafeInteger(receipt.memberCount) || !Number.isSafeInteger(receipt.quarantinedEvidenceCount)
  ) throw new Error("Membership receipt is invalid");
  return receipt;
}
