import { describe, expect, it } from "vitest";
import { createBatchKey } from "./import-service";

describe("learning resource import batches", () => {
  it("creates stable keys for exact replay", () => {
    const input = {
      sourceName: "EbookFoundation/free-programming-books",
      sourceRevision: "a".repeat(40),
      offset: 0,
      limit: 100,
    };
    expect(createBatchKey(input)).toBe(createBatchKey(input));
    expect(createBatchKey(input)).toHaveLength(64);
  });

  it("changes the key when the batch window changes", () => {
    const base = {
      sourceName: "EbookFoundation/free-programming-books",
      sourceRevision: "a".repeat(40),
      offset: 0,
      limit: 100,
    };
    expect(createBatchKey(base)).not.toBe(
      createBatchKey({ ...base, offset: 100 }),
    );
  });
});
