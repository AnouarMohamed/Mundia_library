import { describe, expect, it, vi } from "vitest";
import { FAA_PUBLICATIONS, FAA_SOURCE_NAME, fetchFaaPublications } from "./faa";

describe("FAA learning-resource adapter", () => {
  it("publishes reviewed official PDFs that pass bounded range validation", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(new Uint8Array([0x25, 0x50, 0x44, 0x46]), {
        status: 206,
        headers: {
          "content-type": "application/pdf",
          "content-range": "bytes 0-3/250000000",
        },
      }),
    );

    const result = await fetchFaaPublications({ fetcher });

    expect(result.candidates).toHaveLength(FAA_PUBLICATIONS.length);
    expect(
      result.candidates.every((item) => item.verificationStatus === "VERIFIED"),
    ).toBe(true);
    expect(result.candidates[0]).toMatchObject({
      sourceName: FAA_SOURCE_NAME,
      licenseExpression: "PUBLIC-DOMAIN",
    });
    expect(result.revision).toMatch(/^[0-9a-f]{64}$/u);
  });

  it("quarantines a response that is not a bounded PDF range", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response("not a pdf", {
        status: 200,
        headers: { "content-type": "text/html" },
      }),
    );

    const result = await fetchFaaPublications({ fetcher });

    expect(
      result.candidates.every(
        (item) => item.verificationStatus === "QUARANTINED",
      ),
    ).toBe(true);
    expect(result.candidates.every((item) => item.downloadUrl === null)).toBe(
      true,
    );
  });
});
