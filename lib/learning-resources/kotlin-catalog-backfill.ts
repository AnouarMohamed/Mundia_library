import { createHash } from "crypto";

const MAX_BATCH_SIZE = 250;

export interface CatalogBackfillRecord {
  id: string;
  sourceName: string;
  sourceRecordKey: string;
  title: string;
  author: string | null;
  description: string | null;
  coverUrl: string | null;
  coverAlt: string | null;
  category: string;
  language: string;
  sourceUrl: string;
  licenseExpression: string;
  licenseUrl: string;
  downloadUrl: string | null;
  readUrl: string | null;
  contentHash: string;
  sourceRevision: string;
}

export interface CatalogBackfillBatch {
  importId: string;
  sourceName: string;
  sourceRevision: string;
  items: Array<{
    resourceId: string;
    sourceRecordKey: string;
    title: string;
    author: string | null;
    description: string | null;
    category: string;
    language: string;
    coverUrl: string | null;
    coverAlt: string | null;
    sourceUrl: string;
    licenseExpression: string;
    licenseUrl: string;
    accessMode: "DOWNLOAD" | "READ_AT_SOURCE";
    readUrl: string | null;
    contentSha256: string;
  }>;
}

export function createCatalogBackfillBatches(
  records: CatalogBackfillRecord[],
  batchSize = MAX_BATCH_SIZE,
): CatalogBackfillBatch[] {
  if (!Number.isSafeInteger(batchSize) || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
    throw new Error("Catalog backfill batch size must be between 1 and 250");
  }
  const uniqueIds = new Set(records.map((record) => record.id));
  const uniqueSourceKeys = new Set(
    records.map((record) => `${record.sourceName}\u0000${record.sourceRecordKey}`),
  );
  if (uniqueIds.size !== records.length || uniqueSourceKeys.size !== records.length) {
    throw new Error("Catalog backfill input contains duplicate identities");
  }

  const sorted = records.toSorted((left, right) =>
      left.sourceName.localeCompare(right.sourceName) ||
      left.sourceRevision.localeCompare(right.sourceRevision) ||
      left.sourceRecordKey.localeCompare(right.sourceRecordKey),
    );
  const grouped = new Map<string, CatalogBackfillRecord[]>();
  for (const record of sorted) {
    const key = `${record.sourceName}\u0000${record.sourceRevision}`;
    const group = grouped.get(key) ?? [];
    group.push(record);
    grouped.set(key, group);
  }
  const batches: CatalogBackfillBatch[] = [];
  for (const group of grouped.values()) {
    for (let offset = 0; offset < group.length; offset += batchSize) {
      const chunk = group.slice(offset, offset + batchSize);
      const sourceName = chunk[0]!.sourceName;
      const sourceRevision = chunk[0]!.sourceRevision;
      const identity = createHash("sha256")
        .update("catalog-backfill-v2\u0000")
        .update(sourceName)
        .update("\u0000")
        .update(sourceRevision)
        .update("\u0000")
        .update(chunk.map((record) => `${record.id}:${record.contentHash}`).join("\u0000"))
        .digest();
      identity[6] = (identity[6]! & 0x0f) | 0x50;
      identity[8] = (identity[8]! & 0x3f) | 0x80;
      const hex = identity.subarray(0, 16).toString("hex");
      const importId = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
      batches.push({
        importId,
        sourceName,
        sourceRevision,
        items: chunk.map((record) => ({
          resourceId: record.id,
          sourceRecordKey: record.sourceRecordKey,
          title: record.title,
          author: record.author,
          description: record.description,
          category: record.category,
          language: record.language,
          coverUrl: record.coverUrl,
          coverAlt: record.coverAlt,
          sourceUrl: record.sourceUrl,
          licenseExpression: record.licenseExpression,
          licenseUrl: record.licenseUrl,
          accessMode: record.downloadUrl ? "DOWNLOAD" : "READ_AT_SOURCE",
          readUrl: record.downloadUrl ? null : (record.readUrl ?? record.sourceUrl),
          contentSha256: record.contentHash,
        })),
      });
    }
  }
  return batches;
}

export async function applyCatalogBackfillBatch(input: {
  baseUrl: string;
  bearerToken: string;
  batch: CatalogBackfillBatch;
  fetcher?: typeof fetch;
}) {
  const baseUrl = new URL(input.baseUrl);
  if (baseUrl.protocol !== "https:" && baseUrl.hostname !== "localhost") {
    throw new Error("CATALOG_SERVICE_URL must use HTTPS outside localhost");
  }
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new Error("CATALOG_SERVICE_URL must be a clean service origin");
  }
  if (input.bearerToken.trim().length < 20) {
    throw new Error("CATALOG_IMPORT_BEARER_TOKEN is invalid");
  }
  const fetcher = input.fetcher ?? fetch;
  const endpoint = new URL(
    `/api/v1/catalog/learning-resource-imports/${input.batch.importId}`,
    baseUrl,
  );
  const headers = {
    authorization: `Bearer ${input.bearerToken}`,
    "content-type": "application/json",
  };
  const applied = await fetcher(endpoint, {
    method: "PUT",
    headers,
    body: JSON.stringify({
      sourceName: input.batch.sourceName,
      sourceRevision: input.batch.sourceRevision,
      items: input.batch.items,
    }),
    redirect: "error",
    signal: AbortSignal.timeout(15_000),
  });
  if (!applied.ok) throw new Error(`Catalog import failed with HTTP ${applied.status}`);
  const result = (await applied.json()) as Record<string, unknown>;

  const evidenceResponse = await fetcher(endpoint, {
    headers: { authorization: headers.authorization },
    redirect: "error",
    signal: AbortSignal.timeout(10_000),
  });
  if (!evidenceResponse.ok) {
    throw new Error(`Catalog reconciliation failed with HTTP ${evidenceResponse.status}`);
  }
  const evidence = (await evidenceResponse.json()) as Record<string, unknown>;
  if (
    result.manifestSha256 !== evidence.manifestSha256 ||
    result.recordCount !== evidence.recordCount ||
    evidence.recordCount !== input.batch.items.length
  ) {
    throw new Error("Catalog reconciliation evidence does not match the applied batch");
  }
  return evidence;
}
