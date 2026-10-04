import { describe, expect, it, vi } from "vitest";
import {
  fetchOpenTextbookPage,
  parseOpenTextbookPage,
} from "./open-textbook-library";

const payload = (license = "Attribution") =>
  JSON.stringify({
    data: [
      {
        id: 42,
        title: "Operating Systems for Engineers",
        license,
        language: "eng",
        contributors: [
          { contribution: "Author", first_name: "Ada", last_name: "Author" },
        ],
        formats: [
          { type: "PDF", url: "https://university.example.edu/os.pdf" },
        ],
        url: "https://open.umn.edu/opentextbooks/textbooks/operating-systems",
        updated_at: "2026-10-05T00:00:00Z",
      },
    ],
    links: { total_pages: 2, total_count: 11 },
  });

describe("Open Textbook Library importer", () => {
  it("maps an allowlisted university textbook to a verified download", () => {
    const page = parseOpenTextbookPage(payload(), 3, 1);

    expect(page).toMatchObject({ page: 1, totalPages: 2, totalCount: 11 });
    expect(page.revision).toHaveLength(64);
    expect(page.candidates[0]).toMatchObject({
      sourceRecordKey: "otl:42",
      title: "Operating Systems for Engineers",
      author: "Ada Author",
      category: "Computer Science",
      language: "en",
      licenseExpression: "CC-BY",
      verificationStatus: "VERIFIED",
      downloadUrl: "https://university.example.edu/os.pdf",
    });
  });

  it("quarantines a licence outside the allowlist", () => {
    const page = parseOpenTextbookPage(payload("Attribution-NonCommercial"), 3, 1);
    expect(page.candidates[0]).toMatchObject({
      licenseExpression: null,
      verificationStatus: "QUARANTINED",
    });
  });

  it("labels repository pages as official-source links, not direct downloads", () => {
    const value = JSON.parse(payload()) as {
      data: Array<{ formats: Array<{ type: string; url: string }> }>;
    };
    value.data[0]!.formats[0]!.url =
      "https://github.com/example/book/releases";

    const page = parseOpenTextbookPage(JSON.stringify(value), 3, 1);
    expect(page.candidates[0]).toMatchObject({
      verificationStatus: "VERIFIED",
      downloadUrl: null,
      readUrl: "https://github.com/example/book/releases",
    });
  });

  it("uses only fixed official subject endpoints and filters", async () => {
    const fetcher = vi.fn(async () =>
      new Response(payload(), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    await fetchOpenTextbookPage({ subjectId: 13, page: 2, fetcher });

    const requested = fetcher.mock.calls[0]?.[0].toString() ?? "";
    expect(requested).toContain("/subjects/13.json");
    expect(requested).toContain("formats%5B%5D=PDF");
    expect(requested).toContain("page=2");
    expect(fetcher).toHaveBeenCalledOnce();
  });

  it("rejects unsupported subjects before making a request", async () => {
    const fetcher = vi.fn();
    await expect(
      fetchOpenTextbookPage({ subjectId: 999, page: 1, fetcher }),
    ).rejects.toThrow("OTL_INVALID_SUBJECT");
    expect(fetcher).not.toHaveBeenCalled();
  });
});
