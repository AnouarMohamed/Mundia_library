import { describe, expect, it, vi } from "vitest";
import {
  fetchInternetArchiveBatch,
  parseInternetArchiveItem,
} from "./internet-archive";

const identifier = "nasa_techdoc_19670028324";
const item = (overrides: Record<string, unknown> = {}) => ({
  item_last_updated: 1_700_000_000,
  metadata: {
    identifier,
    mediatype: "texts",
    collection: "nasa_techdocs",
    title: "Helicopters Calculation and Design: Aerodynamics",
    creator: ["A. S. Braverman", "M. L. Mil"],
    description: "An engineering treatment of helicopter aerodynamics.",
    subject: ["AERODYNAMICS", "HELICOPTER DESIGN"],
    language: "English",
    licenseurl: "http://creativecommons.org/licenses/publicdomain/",
    ...overrides,
  },
  files: [
    {
      name: "19670028324.pdf",
      source: "original",
      format: "Text PDF",
      sha1: "a".repeat(40),
    },
  ],
});

describe("Internet Archive NASA importer", () => {
  it("publishes a trusted item with exact public-domain evidence", () => {
    const parsed = parseInternetArchiveItem(identifier, item());

    expect(parsed?.candidate).toMatchObject({
      sourceRecordKey: `internet-archive:${identifier}`,
      title: "Helicopters Calculation and Design: Aerodynamics",
      author: "A. S. Braverman, M. L. Mil",
      category: "Aerospace Engineering",
      language: "en",
      licenseExpression: "PUBLIC-DOMAIN",
      verificationStatus: "VERIFIED",
      sourceUrl: `https://archive.org/details/${identifier}`,
      coverUrl: `https://archive.org/services/img/${identifier}`,
      coverAlt:
        "Official preview of Helicopters Calculation and Design: Aerodynamics",
      downloadUrl: `https://archive.org/download/${identifier}/19670028324.pdf`,
    });
  });

  it.each([
    ["community upload", { collection: "opensource" }],
    ["wrong media type", { mediatype: "movies" }],
  ])(
    "rejects a %s even when it claims public-domain rights",
    (_label, metadata) => {
      expect(parseInternetArchiveItem(identifier, item(metadata))).toBeNull();
    },
  );

  it("quarantines a trusted item when its per-item licence changes", () => {
    const parsed = parseInternetArchiveItem(
      identifier,
      item({ licenseurl: "https://creativecommons.org/licenses/by-nc/4.0/" }),
    );

    expect(parsed?.candidate).toMatchObject({
      licenseExpression: null,
      downloadUrl: null,
      verificationStatus: "QUARANTINED",
    });
  });

  it("rejects path-shaped identifiers", () => {
    expect(parseInternetArchiveItem("../metadata", item())).toBeNull();
  });

  it("uses the official APIs, bounded concurrency, and revision pinning", async () => {
    const fetcher = vi.fn(async (request: URL | RequestInfo) => {
      const url = request.toString();
      if (url.includes("advancedsearch.php")) {
        return Response.json({
          response: {
            numFound: 1,
            docs: [
              {
                identifier,
                title: "Helicopters Calculation and Design: Aerodynamics",
              },
            ],
          },
        });
      }
      return Response.json(item());
    });
    const batch = await fetchInternetArchiveBatch({
      limit: 1,
      fetcher,
      pause: vi.fn(async () => undefined),
    });

    expect(batch).toMatchObject({ matches: 1 });
    expect(batch.revision).toHaveLength(64);
    expect(batch.candidates).toHaveLength(1);
    expect(batch.candidates[0]?.sourceRevision).toBe(batch.revision);
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0]?.[0].toString()).toContain(
      "collection%3Anasa_techdocs",
    );
    expect(fetcher.mock.calls[1]?.[0].toString()).toContain(
      `/metadata/${identifier}`,
    );
  });
});
