import { describe, expect, it, vi } from "vitest";
import {
  fetchEbookFoundationSnapshot,
  parseEbookFoundationCatalog,
} from "./ebook-foundation";

const REVISION = "a".repeat(40);

describe("EbookFoundation importer", () => {
  it("parses, classifies and deduplicates catalog entries", () => {
    const resources = parseEbookFoundationCatalog({
      revision: REVISION,
      content: [
        "### Security & Privacy",
        "* [Open Security](https://books.example/open.pdf) - Ada Author (PDF) (CC BY 4.0)",
        "* [Open Security](https://books.example/open.pdf) - Ada Author (PDF) (CC BY 4.0)",
        "* [Restricted](https://books.example/restricted) - N. C. Author (CC BY-NC-SA)",
        "* [Unknown](http://books.example/read) - Unknown Author (HTML)",
      ].join("\n"),
    });

    expect(resources).toHaveLength(3);
    expect(resources[0]).toMatchObject({
      title: "Open Security",
      author: "Ada Author",
      category: "Security & Privacy",
      licenseExpression: "CC-BY",
      verificationStatus: "VERIFIED",
      downloadUrl: "https://books.example/open.pdf",
      readUrl: null,
    });
    expect(resources[1]).toMatchObject({
      verificationStatus: "QUARANTINED",
      licenseExpression: null,
    });
    expect(resources[2]).toMatchObject({
      sourceUrl: "https://books.example/read",
      readUrl: "https://books.example/read",
      verificationStatus: "QUARANTINED",
    });
    expect(resources.every((resource) => resource.contentHash.length === 64)).toBe(
      true,
    );
  });

  it("rejects malformed source revisions", () => {
    expect(() =>
      parseEbookFoundationCatalog({ revision: "main", content: "" }),
    ).toThrow("EBOOK_FOUNDATION_INVALID_REVISION");
  });

  it("fetches a bounded, pinned GitHub contents response", async () => {
    const content = "### Programming\n* [Book](https://example.test) (CC0)";
    const fetcher = vi.fn(async () =>
      new Response(
        JSON.stringify({
          type: "file",
          encoding: "base64",
          sha: REVISION,
          size: Buffer.byteLength(content),
          content: Buffer.from(content).toString("base64"),
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      ),
    );

    await expect(
      fetchEbookFoundationSnapshot({ revision: REVISION, fetcher }),
    ).resolves.toEqual({
      revision: REVISION,
      content,
    });
    expect(fetcher).toHaveBeenCalledOnce();
    expect(fetcher.mock.calls[0]?.[0].toString()).toContain(`ref=${REVISION}`);
  });

  it("rejects an unsafe source ref before making a request", async () => {
    const fetcher = vi.fn();

    await expect(
      fetchEbookFoundationSnapshot({ revision: "feature/branch", fetcher }),
    ).rejects.toThrow("EBOOK_FOUNDATION_INVALID_REVISION");
    expect(fetcher).not.toHaveBeenCalled();
  });
});
