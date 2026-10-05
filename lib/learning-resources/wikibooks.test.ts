import { describe, expect, it, vi } from "vitest";
import {
  fetchWikibooksCollection,
  parseWikibooksCollection,
} from "./wikibooks";

function payload(categories: string[] = []) {
  return JSON.stringify({
    batchcomplete: true,
    query: {
      pages: [
        {
          pageid: 7201,
          ns: 0,
          title: "Guide to Unix",
          fullurl: "https://en.wikibooks.org/wiki/Guide_to_Unix",
          revisions: [{ revid: 4082964, timestamp: "2022-07-07T05:11:59Z" }],
          categories: [
            { title: "Category:Book:Wikibooks Stacks/Books" },
            { title: "Category:Shelf:UNIX/all books" },
            ...categories.map((title) => ({ title })),
          ],
        },
      ],
      rightsinfo: {
        url: "https://creativecommons.org/licenses/by-sa/4.0/deed.en",
        text: "Creative Commons Attribution-Share Alike 4.0",
      },
    },
  });
}

describe("Wikibooks importer", () => {
  it("builds a verified direct Wikimedia PDF download", () => {
    const batch = parseWikibooksCollection(payload(), "unix");

    expect(batch.revision).toHaveLength(64);
    expect(batch.candidates[0]).toMatchObject({
      sourceRecordKey: "wikibooks:7201",
      title: "Guide to Unix",
      category: "UNIX",
      licenseExpression: "CC-BY-SA",
      verificationStatus: "VERIFIED",
      downloadUrl:
        "https://en.wikibooks.org/api/rest_v1/page/pdf/Guide_to_Unix",
    });
  });

  it.each([
    "Category:Fair use electronic games screenshots",
    "Category:Copyright violations",
  ])("quarantines a record flagged by %s", (category) => {
    const batch = parseWikibooksCollection(payload([category]), "unix");
    expect(batch.candidates[0]).toMatchObject({
      licenseExpression: null,
      downloadUrl: null,
      verificationStatus: "QUARANTINED",
    });
  });

  it("fails closed when the official rights declaration changes", () => {
    const value = JSON.parse(payload()) as {
      query: { rightsinfo: { url: string } };
    };
    value.query.rightsinfo.url = "https://example.com/unknown";
    expect(() => parseWikibooksCollection(JSON.stringify(value), "unix")).toThrow(
      "WIKIBOOKS_UNVERIFIED_RIGHTS",
    );
  });

  it("uses only the fixed official collection endpoint", async () => {
    const fetcher = vi.fn(async (_url: URL | RequestInfo, init?: RequestInit) =>
      init?.method === "HEAD"
        ? new Response(null, {
            status: 200,
            headers: {
              "content-type": "application/pdf",
              "content-disposition": "attachment; filename=book.pdf",
            },
          })
        : new Response(payload(), {
            status: 200,
            headers: { "content-type": "application/json" },
          }),
    );
    const batch = await fetchWikibooksCollection({ collection: "unix", fetcher });

    const requested = new URL(fetcher.mock.calls[0]![0].toString());
    expect(requested.origin).toBe("https://en.wikibooks.org");
    expect(requested.searchParams.get("gcmtitle")).toBe(
      "Category:Shelf:UNIX/all books",
    );
    expect(requested.searchParams.get("siprop")).toBe("rightsinfo");
    expect(requested.searchParams.get("clcategories")).toContain(
      "Category:Copyright violations",
    );
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(batch.candidates[0]?.sourceRevision).toBe(batch.revision);
  });

  it("quarantines a book when the official PDF is not an attachment", async () => {
    const fetcher = vi.fn(async (_url: URL | RequestInfo, init?: RequestInit) =>
      init?.method === "HEAD"
        ? new Response(null, {
            status: 200,
            headers: { "content-type": "text/html" },
          })
        : new Response(payload(), {
            status: 200,
            headers: { "content-type": "application/json" },
          }),
    );

    const batch = await fetchWikibooksCollection({ collection: "unix", fetcher });
    expect(batch.candidates[0]).toMatchObject({
      verificationStatus: "QUARANTINED",
      downloadUrl: null,
      readUrl: "https://en.wikibooks.org/wiki/Guide_to_Unix",
    });
  });

  it("rejects truncated collections rather than silently omitting books", () => {
    const value = JSON.parse(payload()) as Record<string, unknown>;
    value.continue = { gcmcontinue: "page|123", continue: "gcmcontinue||" };
    expect(() => parseWikibooksCollection(JSON.stringify(value), "unix")).toThrow(
      "WIKIBOOKS_COLLECTION_TOO_LARGE",
    );
  });
});
