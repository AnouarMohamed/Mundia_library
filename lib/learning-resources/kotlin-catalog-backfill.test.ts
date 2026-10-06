import { describe, expect, it, vi } from "vitest";
import {
  applyCatalogBackfillBatch,
  createCatalogBackfillBatches,
  type CatalogBackfillRecord,
} from "./kotlin-catalog-backfill";

const record = (key: string): CatalogBackfillRecord => ({
  id: `10000000-0000-4000-8000-${key.padStart(12, "0")}`,
  sourceName: "Open University",
  sourceRecordKey: key,
  title: `Engineering ${key}`,
  author: "Open Faculty",
  description: null,
  coverUrl: null,
  coverAlt: null,
  category: "Engineering",
  language: "en",
  sourceUrl: `https://example.edu/books/${key}`,
  licenseExpression: "CC-BY",
  licenseUrl: "https://creativecommons.org/licenses/by/4.0/",
  downloadUrl: `https://example.edu/books/${key}.pdf`,
  readUrl: null,
  contentHash: key.padStart(64, "a"),
  sourceRevision: "b".repeat(64),
});

describe("Kotlin catalog learning-resource backfill", () => {
  it("creates stable bounded batch identities", () => {
    const first = createCatalogBackfillBatches([record("1"), record("2")], 1);
    const second = createCatalogBackfillBatches([record("2"), record("1")], 1);
    expect(first).toEqual(second);
    expect(first).toHaveLength(2);
    expect(first[0]!.importId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("reconciles the immutable receipt without exposing the token", async () => {
    const [batch] = createCatalogBackfillBatches([record("1")]);
    const receipt = { manifestSha256: "c".repeat(64), recordCount: 1 };
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }));
    await expect(
      applyCatalogBackfillBatch({
        baseUrl: "https://catalog.example.test",
        bearerToken: "secret-token-that-is-never-logged",
        batch: batch!,
        fetcher,
      }),
    ).resolves.toEqual(receipt);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
