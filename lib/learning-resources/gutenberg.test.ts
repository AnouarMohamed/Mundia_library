import { describe, expect, it, vi } from "vitest";
import {
  fetchGutenbergPage,
  parseDetailPage,
  parseListPage,
} from "./gutenberg";

const listXml = `<?xml version="1.0"?><feed>
<updated>2026-10-05T00:00:00Z</updated>
<link rel="next" href="/ebooks/bookshelf/671.opds?start_index=26"/>
<entry><id>https://www.gutenberg.org/ebooks/123.opds</id></entry>
</feed>`;

const detailXml = (rights: string) => `<?xml version="1.0"?><feed>
<updated>2026-10-05T00:00:00Z</updated><entry>
<id>urn:gutenberg:123:2</id><title>Practical Engineering</title>
<rights>${rights}</rights><author><name>Ada Engineer</name></author>
<dcterms:language>eng</dcterms:language>
<category scheme="http://purl.org/dc/terms/LCSH" term="Engineering"/>
</entry></feed>`;

describe("Project Gutenberg importer", () => {
  it("parses bounded official list entries", () => {
    expect(parseListPage(listXml)).toEqual({
      ebookIds: [123],
      hasNextPage: true,
    });
  });

  it("parses per-book rights and authors", () => {
    expect(
      parseDetailPage(detailXml("Public domain in the USA."), 123),
    ).toEqual({
      ebookId: 123,
      title: "Practical Engineering",
      authors: ["Ada Engineer"],
      language: "en",
      rights: ["Public domain in the USA."],
      subjects: ["Engineering"],
    });
  });

  it("publishes public-domain metadata but honors the canonical-link policy", async () => {
    const fetcher = vi.fn(async (url: URL | RequestInfo, init?: RequestInit) =>
      init?.method === "HEAD"
        ? new Response(null, {
            status: 200,
            headers: {
              "content-type": "image/jpeg",
              "content-length": "2048",
            },
          })
        : new Response(
            url.toString().endsWith("123.opds")
              ? detailXml("Public domain in the USA.")
              : listXml,
            {
              status: 200,
              headers: { "content-type": "application/atom+xml" },
            },
          ),
    );
    const page = await fetchGutenbergPage({
      collection: "engineering",
      page: 1,
      fetcher,
    });

    expect(page.candidates[0]).toMatchObject({
      sourceRecordKey: "gutenberg:123",
      category: "Engineering",
      licenseExpression: "PUBLIC-DOMAIN",
      verificationStatus: "VERIFIED",
      sourceUrl: "https://www.gutenberg.org/ebooks/123",
      readUrl: "https://www.gutenberg.org/ebooks/123",
      downloadUrl: null,
      coverUrl:
        "https://www.gutenberg.org/cache/epub/123/pg123.cover.medium.jpg",
      coverAlt: "Official cover of Practical Engineering",
    });
    expect(page.candidates[0]?.sourceRevision).toBe(page.revision);
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("quarantines copyrighted permission titles", async () => {
    const fetcher = vi.fn(async (url: URL | RequestInfo, init?: RequestInit) =>
      init?.method === "HEAD"
        ? new Response(null, {
            status: 200,
            headers: {
              "content-type": "image/jpeg",
              "content-length": "2048",
            },
          })
        : new Response(
            url.toString().endsWith("123.opds")
              ? detailXml("Copyrighted.")
              : listXml,
            {
              status: 200,
              headers: { "content-type": "application/atom+xml" },
            },
          ),
    );
    const page = await fetchGutenbergPage({
      collection: "engineering",
      page: 1,
      fetcher,
    });
    expect(page.candidates[0]).toMatchObject({
      licenseExpression: null,
      readUrl: null,
      coverUrl: null,
      verificationStatus: "QUARANTINED",
    });
  });

  it("rejects entity-bearing XML and mismatched item identifiers", () => {
    expect(() =>
      parseListPage("<!DOCTYPE x [<!ENTITY y 'z'>]><feed/>"),
    ).toThrow("GUTENBERG_UNSAFE_XML");
    expect(() =>
      parseDetailPage(detailXml("Public domain in the USA."), 999),
    ).toThrow("GUTENBERG_DETAIL_ID_MISMATCH");
  });

  it("rejects unsupported pages before fetching", async () => {
    const fetcher = vi.fn();
    await expect(
      fetchGutenbergPage({ collection: "engineering", page: 0, fetcher }),
    ).rejects.toThrow("GUTENBERG_INVALID_PAGE");
    expect(fetcher).not.toHaveBeenCalled();
  });
});
