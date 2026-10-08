import { createHash } from "node:crypto";

const WORK_NAMESPACE = "3194b12f-f4fd-5bd1-a396-6a985eec7648";
const CONTRIBUTOR_NAMESPACE = "9fcc523e-76ae-5f2a-896a-74bb47cff7bb";
const MAX_BATCH_SIZE = 10;
const MAX_REQUEST_BYTES = 120_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HEX_COLOR = /^#[0-9a-f]{6}$/i;

export interface LegacyCatalogRecord {
  id: string;
  title: string;
  author: string;
  genre: string;
  rating: number;
  ratingCount: number;
  summary: string;
  description: string;
  isbn: string | null;
  publisher: string | null;
  publicationYear: number | null;
  language: string | null;
  pageCount: number | null;
  coverUrl: string;
  coverColor: string;
  videoUrl: string;
  isActive: boolean;
  createdAt: Date | null;
  updatedAt: Date | null;
  reviews: LegacyCatalogReviewRecord[];
}

export interface LegacyCatalogReviewRecord {
  id: string;
  userId: string;
  rating: number;
  comment: string;
  createdAt: Date | null;
  updatedAt: Date | null;
}

export interface LegacyCatalogImportItem {
  workId: string;
  editionId: string;
  contributorId: string;
  title: string;
  author: string;
  summary: string;
  description: string;
  genre: string;
  rating: number;
  ratingCount: number;
  isbn: string;
  publisher: string;
  publicationYear: number;
  language: string;
  pageCount: number;
  coverUrl: string | null;
  coverColor: string | null;
  videoUrl: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  reviews: LegacyCatalogReviewImportItem[];
  contentSha256: string;
}

export interface LegacyCatalogReviewImportItem {
  reviewId: string;
  memberId: string;
  rating: number;
  content: string;
  createdAt: string;
  updatedAt: string;
}

export interface LegacyCatalogImportBatch {
  importId: string;
  sourceRevision: string;
  items: LegacyCatalogImportItem[];
}

export interface LegacyCatalogPlan {
  status: "READY" | "BLOCKED";
  sourceRevision: string;
  sourceCount: number;
  batches: LegacyCatalogImportBatch[];
  findings: Array<{ editionId: string; fields: string[] }>;
}

export function planLegacyCatalogBackfill(
  records: LegacyCatalogRecord[],
  batchSize = MAX_BATCH_SIZE,
): LegacyCatalogPlan {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
    throw new Error("Catalog migration batch size must be between 1 and 10");
  }
  const ids = new Set(records.map((record) => record.id));
  if (ids.size !== records.length) throw new Error("Legacy catalog contains duplicate book IDs");
  const findings: LegacyCatalogPlan["findings"] = [];
  const items: LegacyCatalogImportItem[] = [];
  const isbns = new Map<string, string>();
  const reviewIds = new Set<string>();
  for (const record of records.toSorted((left, right) => left.id.localeCompare(right.id))) {
    const missing = missingRequiredFields(record);
    const isbn = record.isbn?.trim().toLowerCase();
    if (isbn) {
      const owner = isbns.get(isbn);
      if (owner) {
        missing.push(`isbnDuplicate:${owner}`);
      } else {
        isbns.set(isbn, record.id);
      }
    }
    for (const review of record.reviews) {
      if (reviewIds.has(review.id)) missing.push(`reviewIdDuplicate:${review.id}`);
      reviewIds.add(review.id);
    }
    if (missing.length > 0) {
      findings.push({ editionId: record.id, fields: [...new Set(missing)].sort() });
      continue;
    }
    const item = normalizeRecord(record);
    items.push({ ...item, contentSha256: itemSha256(item) });
  }
  const sourceRevision = digest({
    version: "legacy-catalog-source-v1",
    records: records.toSorted((left, right) => left.id.localeCompare(right.id)).map(sourceRecord),
  });
  const batches: LegacyCatalogImportBatch[] = [];
  if (findings.length === 0) {
    let chunk: LegacyCatalogImportItem[] = [];
    for (const item of items) {
      const candidate = [...chunk, item];
      if (chunk.length > 0 && (candidate.length > batchSize || countReviews(candidate) > 1_000 || requestBytes(sourceRevision, candidate) > MAX_REQUEST_BYTES)) {
        batches.push({
          importId: uuidV5(`${sourceRevision}:${chunk.map((item) => item.editionId).join(",")}`, WORK_NAMESPACE),
          sourceRevision,
          items: chunk,
        });
        chunk = [];
      }
      if (requestBytes(sourceRevision, [item]) > MAX_REQUEST_BYTES) {
        findings.push({ editionId: item.editionId, fields: ["requestBodyTooLarge"] });
        continue;
      }
      if (item.reviews.length > 1_000) {
        findings.push({ editionId: item.editionId, fields: ["reviewCountTooLarge"] });
        continue;
      }
      chunk.push(item);
    }
    if (chunk.length > 0) {
      batches.push({
        importId: uuidV5(`${sourceRevision}:${chunk.map((item) => item.editionId).join(",")}`, WORK_NAMESPACE),
        sourceRevision,
        items: chunk,
      });
    }
  }
  if (records.length === 0) findings.push({ editionId: "source", fields: ["emptyCatalog"] });
  if (findings.length > 0) batches.length = 0;
  return {
    status: findings.length === 0 && records.length > 0 ? "READY" : "BLOCKED",
    sourceRevision,
    sourceCount: records.length,
    batches,
    findings,
  };
}

export async function applyLegacyCatalogBatch(input: {
  baseUrl: string;
  bearerToken: string;
  batch: LegacyCatalogImportBatch;
  fetcher?: typeof fetch;
}) {
  const baseUrl = new URL(input.baseUrl);
  if (baseUrl.protocol !== "https:" && baseUrl.hostname !== "localhost") {
    throw new Error("CATALOG_SERVICE_URL must use HTTPS outside localhost");
  }
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new Error("CATALOG_SERVICE_URL must be a clean service origin");
  }
  if (input.bearerToken.trim().length < 20) throw new Error("CATALOG_IMPORT_BEARER_TOKEN is invalid");
  const endpoint = new URL(`/api/v1/catalog/legacy-imports/${input.batch.importId}`, baseUrl);
  const authorization = `Bearer ${input.bearerToken}`;
  const fetcher = input.fetcher ?? fetch;
  const applied = await fetcher(endpoint, {
    method: "PUT",
    headers: { authorization, "content-type": "application/json" },
    body: JSON.stringify({ sourceRevision: input.batch.sourceRevision, items: input.batch.items }),
    redirect: "error",
    signal: AbortSignal.timeout(15_000),
  });
  if (!applied.ok) throw new Error(`Catalog import failed with HTTP ${applied.status}`);
  const result = await safeReceipt(applied);
  const reconciled = await fetcher(endpoint, {
    headers: { authorization }, redirect: "error", signal: AbortSignal.timeout(10_000),
  });
  if (!reconciled.ok) throw new Error(`Catalog reconciliation failed with HTTP ${reconciled.status}`);
  const evidence = await safeReceipt(reconciled);
  if (
    result.manifestSha256 !== evidence.manifestSha256 ||
    result.sourceRevision !== input.batch.sourceRevision ||
    evidence.sourceRevision !== input.batch.sourceRevision ||
    evidence.workCount !== input.batch.items.length ||
    evidence.editionCount !== input.batch.items.length ||
    evidence.reviewCount !== input.batch.items.reduce((total, item) => total + item.reviews.length, 0)
  ) throw new Error("Catalog reconciliation evidence does not match the applied batch");
  return evidence;
}

function normalizeRecord(record: LegacyCatalogRecord): Omit<LegacyCatalogImportItem, "contentSha256"> {
  const title = record.title.trim();
  const author = record.author.trim();
  const createdAt = requireDate(record.createdAt, "createdAt");
  const updatedAt = requireDate(record.updatedAt, "updatedAt");
  const reviews = record.reviews
    .toSorted((left, right) => left.id.localeCompare(right.id))
    .map((review) => ({
      reviewId: review.id.toLowerCase(),
      memberId: review.userId.toLowerCase(),
      rating: review.rating,
      content: review.comment.trim(),
      createdAt: requireDate(review.createdAt, "review.createdAt").toISOString(),
      updatedAt: requireDate(review.updatedAt, "review.updatedAt").toISOString(),
    }));
  const rating = reviews.length === 0 ? 0 :
    Math.round((reviews.reduce((sum, review) => sum + review.rating, 0) / reviews.length) * 100) / 100;
  return {
    workId: uuidV5(`work:${record.id.toLowerCase()}`, WORK_NAMESPACE),
    editionId: record.id.toLowerCase(),
    contributorId: uuidV5(`author:${author.toLocaleLowerCase("en-US")}`, CONTRIBUTOR_NAMESPACE),
    title,
    author,
    summary: record.summary.trim(),
    description: record.description.trim(),
    genre: record.genre.trim(),
    rating,
    ratingCount: reviews.length,
    isbn: record.isbn!.trim(),
    publisher: record.publisher!.trim(),
    publicationYear: record.publicationYear!,
    language: record.language!.trim(),
    pageCount: record.pageCount!,
    coverUrl: optionalUrl(record.coverUrl),
    coverColor: optionalText(record.coverColor)?.toUpperCase() ?? null,
    videoUrl: optionalUrl(record.videoUrl),
    isActive: record.isActive,
    createdAt: createdAt.toISOString(),
    updatedAt: updatedAt.toISOString(),
    reviews,
  };
}

function missingRequiredFields(record: LegacyCatalogRecord): string[] {
  const missing: string[] = [];
  if (!UUID.test(record.id)) missing.push("id");
  const requiredText = { title: record.title, author: record.author, genre: record.genre,
    isbn: record.isbn, publisher: record.publisher, language: record.language };
  for (const [name, value] of Object.entries(requiredText)) {
    if (typeof value !== "string" || value.trim().length === 0) missing.push(name);
  }
  if (!Number.isInteger(record.publicationYear) || record.publicationYear! < 1000 || record.publicationYear! > 3000) missing.push("publicationYear");
  if (!Number.isInteger(record.pageCount) || record.pageCount! < 1 || record.pageCount! > 100_000) missing.push("pageCount");
  if (!record.createdAt || !Number.isFinite(record.createdAt.valueOf())) missing.push("createdAt");
  if (!record.updatedAt || !Number.isFinite(record.updatedAt.valueOf())) missing.push("updatedAt");
  if (record.createdAt && record.updatedAt && record.updatedAt < record.createdAt) missing.push("timestampOrder");
  if (!Number.isFinite(record.rating) || record.rating < 0 || record.rating > 5) missing.push("rating");
  if (!Number.isSafeInteger(record.ratingCount) || record.ratingCount < 0) missing.push("ratingCount");
  if (record.reviews.length !== record.ratingCount) missing.push("ratingCountMismatch");
  for (const review of record.reviews) {
    if (!UUID.test(review.id)) missing.push(`review.id:${review.id}`);
    if (!UUID.test(review.userId)) missing.push(`review.userId:${review.id}`);
    if (!Number.isInteger(review.rating) || review.rating < 1 || review.rating > 5) missing.push(`review.rating:${review.id}`);
    if (!review.comment.trim() || review.comment.trim().length > 4_000) missing.push(`review.comment:${review.id}`);
    if (!review.createdAt || !Number.isFinite(review.createdAt.valueOf())) missing.push(`review.createdAt:${review.id}`);
    if (!review.updatedAt || !Number.isFinite(review.updatedAt.valueOf())) missing.push(`review.updatedAt:${review.id}`);
    if (review.createdAt && review.updatedAt && review.updatedAt < review.createdAt) missing.push(`review.timestampOrder:${review.id}`);
  }
  if (new Set(record.reviews.map((review) => review.userId)).size !== record.reviews.length) missing.push("reviewMemberDuplicate");
  for (const [name, value] of [["coverUrl", record.coverUrl], ["videoUrl", record.videoUrl]] as const) {
    if (value.trim() && !isPublicHttpsUrl(value)) missing.push(name);
  }
  if (record.coverColor.trim() && !HEX_COLOR.test(record.coverColor.trim())) missing.push("coverColor");
  return missing.sort();
}

function itemSha256(item: Omit<LegacyCatalogImportItem, "contentSha256">): string {
  const values = ["catalog-legacy-item-v1", item.workId, item.editionId, item.contributorId,
    item.title, item.author, item.summary, item.description, item.genre, ratingText(item.rating),
    String(item.ratingCount), item.isbn, item.publisher, String(item.publicationYear), item.language,
    String(item.pageCount), item.coverUrl ?? "", item.coverColor ?? "", item.videoUrl ?? "",
    String(item.isActive), String(new Date(item.createdAt).valueOf()), String(new Date(item.updatedAt).valueOf()),
    ...item.reviews.flatMap((review) => [review.reviewId, review.memberId, String(review.rating), review.content,
      String(new Date(review.createdAt).valueOf()), String(new Date(review.updatedAt).valueOf())])];
  return createHash("sha256").update(lengthPrefixed(values)).digest("hex");
}

function lengthPrefixed(values: string[]): string {
  return values[0]! + values.slice(1).map((value) => `\u001f${value.length}:${value}`).join("");
}

function ratingText(value: number): string { return Number.isInteger(value) ? value.toFixed(1) : String(value); }
function optionalText(value: string): string | null { return value.trim() || null; }
function optionalUrl(value: string): string | null {
  const text = optionalText(value);
  return text === null ? null : canonicalPublicHttpsUrl(text);
}
function requireDate(value: Date | null, name: string): Date {
  if (!value || !Number.isFinite(value.valueOf())) throw new Error(`${name} is required`);
  return value;
}
function sourceRecord(record: LegacyCatalogRecord) {
  return { ...record, createdAt: record.createdAt?.toISOString() ?? null, updatedAt: record.updatedAt?.toISOString() ?? null,
    reviews: record.reviews.toSorted((left, right) => left.id.localeCompare(right.id)).map((review) => ({ ...review,
      createdAt: review.createdAt?.toISOString() ?? null, updatedAt: review.updatedAt?.toISOString() ?? null })) };
}
function requestBytes(sourceRevision: string, items: LegacyCatalogImportItem[]): number {
  return Buffer.byteLength(JSON.stringify({ sourceRevision, items }), "utf8");
}
function countReviews(items: LegacyCatalogImportItem[]): number {
  return items.reduce((total, item) => total + item.reviews.length, 0);
}
function isPublicHttpsUrl(value: string): boolean {
  try { canonicalPublicHttpsUrl(value); return true; } catch { return false; }
}
function canonicalPublicHttpsUrl(value: string): string {
  try {
    const text = value.trim();
    const url = new URL(text);
    const rawPath = text.replace(/^https:\/\/[^/]+/i, "").split(/[?#]/, 1)[0] || "/";
    if (!text.startsWith("https://") || url.protocol !== "https:" || url.username || url.password || url.port || url.hash ||
      /^https:\/\/[^/?#]+:\d+/i.test(text) || /^(localhost|[0-9.]+)$/i.test(url.hostname) ||
      url.hostname.endsWith(".local") || url.hostname.endsWith(".internal") || url.hostname.includes(":") ||
      /%(?:2e|2f|5c)/i.test(rawPath) || /\/(?:\.{1,2})(?:\/|$)/.test(rawPath)) {
      throw new Error("URL must be canonical public HTTPS");
    }
    return url.toString();
  } catch (error) {
    if (error instanceof Error && error.message === "URL must be canonical public HTTPS") throw error;
    throw new Error("URL must be canonical public HTTPS");
  }
}
function digest(value: unknown): string {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}
function uuidV5(name: string, namespace: string): string {
  const namespaceBytes = Buffer.from(namespace.replaceAll("-", ""), "hex");
  if (namespaceBytes.length !== 16) throw new Error("UUID namespace is invalid");
  const bytes = createHash("sha1").update(namespaceBytes).update(name, "utf8").digest().subarray(0, 16);
  bytes[6] = (bytes[6]! & 0x0f) | 0x50;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
async function safeReceipt(response: Response): Promise<Record<string, unknown>> {
  const value: unknown = await response.json();
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("Catalog receipt is invalid");
  const receipt = value as Record<string, unknown>;
  if (
    typeof receipt.manifestSha256 !== "string" || !/^[0-9a-f]{64}$/.test(receipt.manifestSha256) ||
    typeof receipt.sourceRevision !== "string" || !/^[0-9a-f]{64}$/.test(receipt.sourceRevision) ||
    !Number.isSafeInteger(receipt.workCount) || !Number.isSafeInteger(receipt.editionCount) ||
    !Number.isSafeInteger(receipt.reviewCount)
  ) throw new Error("Catalog receipt is invalid");
  return receipt;
}
