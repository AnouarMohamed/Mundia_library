import { describe, expect, it, vi } from "vitest";
import {
  applyLegacyCatalogBatch,
  planLegacyCatalogBackfill,
  type LegacyCatalogRecord,
} from "./legacy-catalog-backfill";

const record = (suffix: string): LegacyCatalogRecord => ({
  id: `10000000-0000-4000-8000-${suffix.padStart(12, "0")}`,
  title: `Reliable Systems ${suffix}`,
  author: "Ada Engineer",
  genre: "Engineering",
  rating: 1,
  ratingCount: 1,
  summary: "A concise summary",
  description: "A complete description",
  isbn: `978000000${suffix.padStart(4, "0")}`,
  publisher: "Open Faculty Press",
  publicationYear: 2025,
  language: "English",
  pageCount: 320,
  coverUrl: "https://example.edu/cover.jpg",
  coverColor: "#123abc",
  videoUrl: "",
  isActive: true,
  createdAt: new Date("2025-04-01T09:00:00Z"),
  updatedAt: new Date("2025-04-02T09:00:00Z"),
  reviews: [{
    id: `20000000-0000-4000-8000-${suffix.padStart(12, "0")}`,
    userId: `30000000-0000-4000-8000-${suffix.padStart(12, "0")}`,
    rating: 5,
    comment: "Excellent systems guidance.",
    createdAt: new Date("2025-04-01T09:00:00Z"),
    updatedAt: new Date("2025-04-02T09:00:00Z"),
  }],
});

describe("legacy physical catalog backfill", () => {
  it("creates stable complete batches and derives ratings from reviews", () => {
    const first = planLegacyCatalogBackfill([record("1"), record("2")], 1);
    const second = planLegacyCatalogBackfill([record("2"), record("1")], 1);
    expect(first).toEqual(second);
    expect(first.status).toBe("READY");
    expect(first.batches).toHaveLength(2);
    expect(first.batches[0]!.items[0]).toMatchObject({ rating: 5, ratingCount: 1, coverColor: "#123ABC" });
  });

  it("blocks invalid URLs and global ISBN collisions before creating batches", () => {
    const duplicate = record("2");
    duplicate.isbn = record("1").isbn;
    duplicate.coverUrl = "https://127.0.0.1/private.jpg";
    const plan = planLegacyCatalogBackfill([record("1"), duplicate]);
    expect(plan.status).toBe("BLOCKED");
    expect(plan.batches).toEqual([]);
    expect(plan.findings.flatMap((finding) => finding.fields)).toContain("coverUrl");
    expect(plan.findings.flatMap((finding) => finding.fields).some((field) => field.startsWith("isbnDuplicate:"))).toBe(true);
  });

  it("applies then reconciles immutable work edition and review counts", async () => {
    const plan = planLegacyCatalogBackfill([record("1")]);
    const batch = plan.batches[0]!;
    const receipt = {
      sourceRevision: batch.sourceRevision,
      manifestSha256: "c".repeat(64),
      workCount: 1,
      editionCount: 1,
      contributorCount: 1,
      reviewCount: 1,
    };
    const fetcher = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify(receipt), { status: 200 }));
    await expect(applyLegacyCatalogBatch({
      baseUrl: "https://catalog.example.test",
      bearerToken: "migration-token-that-is-never-logged",
      batch,
      fetcher,
    })).resolves.toEqual(receipt);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
