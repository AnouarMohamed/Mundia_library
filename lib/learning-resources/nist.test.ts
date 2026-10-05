import { describe, expect, it, vi } from "vitest";
import {
  extractOfficialPdfUrl,
  fetchNistPublications,
  isProxiableNistDownload,
  NIST_PUBLICATIONS,
  NIST_SOURCE_NAME,
} from "./nist";

describe("NIST learning-resource adapter", () => {
  it("extracts only official HTTPS NIST PDF URLs", () => {
    expect(
      extractOfficialPdfUrl(
        '<a href="https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf">Download URL</a>',
      ),
    ).toBe(
      "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf",
    );
    expect(
      extractOfficialPdfUrl("https://evil.example/NIST.SP.800-204A.pdf"),
    ).toBeNull();
  });

  it("verifies every manifest record from its official page and bounded PDF", async () => {
    const fetcher = vi.fn(
      async (_url: string | URL | Request, init?: RequestInit) => {
        if (new Headers(init?.headers).has("range")) {
          return new Response(new Uint8Array([0x25, 0x50, 0x44, 0x46]), {
            status: 206,
            headers: {
              "content-type": "application/pdf",
              "content-range": "bytes 0-3/1000",
            },
          });
        }
        return new Response(
          '<a href="https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf">Download URL</a>',
          { status: 200, headers: { "content-type": "text/html" } },
        );
      },
    );

    const result = await fetchNistPublications({
      fetcher: fetcher as typeof fetch,
    });

    expect(result.candidates).toHaveLength(NIST_PUBLICATIONS.length);
    expect(
      result.candidates.every((item) => item.verificationStatus === "VERIFIED"),
    ).toBe(true);
    expect(result.candidates[0]).toMatchObject({
      sourceName: NIST_SOURCE_NAME,
      licenseExpression: "PUBLIC-DOMAIN",
      downloadUrl:
        "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf",
    });
    expect(result.revision).toMatch(/^[0-9a-f]{64}$/u);
  });

  it("quarantines PDFs that exceed the proxy limit", async () => {
    const fetcher = vi.fn(
      async (_url: string | URL | Request, init?: RequestInit) => {
        if (new Headers(init?.headers).has("range")) {
          return new Response(new Uint8Array([0x25, 0x50, 0x44, 0x46]), {
            status: 206,
            headers: {
              "content-type": "application/pdf",
              "content-range": "bytes 0-3/4000001",
            },
          });
        }
        return new Response(
          "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf",
          { status: 200, headers: { "content-type": "text/html" } },
        );
      },
    );

    const result = await fetchNistPublications({
      fetcher: fetcher as typeof fetch,
    });

    expect(
      result.candidates.every(
        (item) => item.verificationStatus === "QUARANTINED",
      ),
    ).toBe(true);
    expect(result.candidates.every((item) => item.downloadUrl === null)).toBe(
      true,
    );
  });

  it("allows proxying only the exact public-domain NIST source boundary", () => {
    expect(
      isProxiableNistDownload({
        sourceName: NIST_SOURCE_NAME,
        licenseExpression: "PUBLIC-DOMAIN",
        downloadUrl:
          "https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-204A.pdf",
      }),
    ).toBe(true);
    expect(
      isProxiableNistDownload({
        sourceName: NIST_SOURCE_NAME,
        licenseExpression: "PUBLIC-DOMAIN",
        downloadUrl: "https://example.com/book.pdf",
      }),
    ).toBe(false);
  });
});
