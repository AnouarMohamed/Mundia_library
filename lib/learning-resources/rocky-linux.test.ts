import { describe, expect, it, vi } from "vitest";
import {
  fetchRockyLinuxBooks,
  ROCKY_LINUX_BOOKS,
  ROCKY_LINUX_LICENSE_URL,
} from "./rocky-linux";

describe("Rocky Linux importer", () => {
  it("verifies official courseware and its project licence", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      if (String(input) === ROCKY_LINUX_LICENSE_URL) {
        return new Response(
          "Creative Commons Attribution-ShareAlike 4.0 International",
          { status: 200, headers: { "content-type": "text/plain" } },
        );
      }
      return new Response(null, {
        status: 200,
        headers: {
          "content-type": "application/pdf",
          etag: '"version-1"',
          "content-length": "10000000",
        },
      });
    });

    const batch = await fetchRockyLinuxBooks({ fetcher });
    expect(batch.revision).toMatch(/^[0-9a-f]{64}$/u);
    expect(batch.candidates).toHaveLength(ROCKY_LINUX_BOOKS.length);
    expect(batch.candidates[0]).toMatchObject({
      licenseExpression: "CC-BY-SA",
      verificationStatus: "VERIFIED",
      category: "Linux & UNIX Administration",
      coverUrl: null,
    });
    expect(
      batch.candidates.every((item) => item.downloadUrl?.endsWith(".pdf")),
    ).toBe(true);
  });

  it("fails closed when a document is not an official PDF", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) =>
      String(input) === ROCKY_LINUX_LICENSE_URL
        ? new Response(
            "Creative Commons Attribution-ShareAlike 4.0 International",
            { status: 200, headers: { "content-type": "text/plain" } },
          )
        : new Response(null, {
            status: 200,
            headers: { "content-type": "text/html" },
          }),
    );
    await expect(fetchRockyLinuxBooks({ fetcher })).rejects.toThrow(
      "ROCKY_LINUX_PDF_HTTP_200",
    );
  });
});
