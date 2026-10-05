import { describe, expect, it, vi } from "vitest";
import { fetchArxivPage, parseArxivPage } from "./arxiv";

const xml = (license: string) => `<?xml version="1.0"?>
<OAI-PMH><ListRecords><record><header><identifier>oai:arXiv.org:2609.12345</identifier><datestamp>2026-10-01</datestamp></header>
<metadata><arXiv><id>2609.12345</id><authors><author><keyname>Lovelace</keyname><forenames>Ada</forenames></author></authors>
<title>Secure Distributed Systems</title><categories>cs.CR cs.DC</categories><license>${license}</license>
<abstract>A practical analysis of resilient distributed security.</abstract></arXiv></metadata></record>
<resumptionToken>next-page</resumptionToken></ListRecords></OAI-PMH>`;

describe("arXiv importer", () => {
  it("publishes papers carrying an allowlisted per-paper licence", () => {
    const page = parseArxivPage(
      xml("http://creativecommons.org/licenses/by/4.0/"),
    );
    expect(page).toMatchObject({ sourceRecords: 1, selectedRecords: 1 });
    expect(page.revision).toHaveLength(64);
    expect(page.candidates[0]).toMatchObject({
      sourceRecordKey: "arxiv:2609.12345",
      title: "Secure Distributed Systems",
      author: "Ada Lovelace",
      category: "Security",
      licenseExpression: "CC-BY",
      verificationStatus: "VERIFIED",
      sourceUrl: "https://arxiv.org/abs/2609.12345",
      downloadUrl: "https://arxiv.org/pdf/2609.12345",
    });
  });

  it.each([
    "http://arxiv.org/licenses/nonexclusive-distrib/1.0/",
    "http://creativecommons.org/licenses/by-nc-sa/4.0/",
    "CC BY 4.0",
  ])("quarantines a paper outside the allowlist", (license) => {
    expect(parseArxivPage(xml(license)).candidates[0]).toMatchObject({
      licenseExpression: null,
      downloadUrl: null,
      verificationStatus: "QUARANTINED",
    });
  });

  it("rejects entity-bearing XML", () => {
    expect(() =>
      parseArxivPage("<!DOCTYPE x [<!ENTITY y 'z'>]><x>&y;</x>"),
    ).toThrow("ARXIV_UNSAFE_XML");
  });

  it("builds a bounded official OAI request", async () => {
    const fetcher = vi.fn(
      async () =>
        new Response(xml("https://creativecommons.org/licenses/by-sa/4.0/"), {
          status: 200,
          headers: { "content-type": "text/xml;charset=UTF-8" },
        }),
    );
    await fetchArxivPage({
      set: "cs:cs:CR",
      from: "2026-10-01",
      limit: 10,
      fetcher,
    });
    const url = fetcher.mock.calls[0]?.[0].toString() ?? "";
    expect(url).toContain("metadataPrefix=arXiv");
    expect(url).toContain("set=cs%3Acs%3ACR");
    expect(url).toContain("from=2026-10-01");
  });
});
