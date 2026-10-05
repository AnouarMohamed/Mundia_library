import { describe, expect, it, vi } from "vitest";
import { fetchDoabPage, parseDoabPage } from "./doab";

const xml = (rights: string, rightsUri: string) => `<?xml version="1.0"?>
<OAI-PMH><ListRecords><record><header><identifier>oai:doabooks.org:test-1</identifier></header>
<metadata><metadata><element name="dc">
<element name="contributor"><element name="author"><element name="none"><field name="value">Ada Author</field></element></element></element>
<element name="identifier"><element name="uri"><element name="none"><field name="value">https://directory.doabooks.org/handle/test-1</field></element></element></element>
<element name="language"><element name="none"><field name="value">eng</field></element></element>
<element name="description"><element name="abstract"><field name="value">A practical treatment of resilient distributed computing.</field></element></element>
<element name="subject"><element name="classification"><element name="en_US"><field name="value">Computer science</field></element></element></element>
<element name="title"><element name="none"><field name="value">Reliable Distributed Systems</field></element></element>
</element><element name="bundles"><element name="bundle"><element name="bitstreams">
<element name="bitstream"><field name="description">Cover</field><field name="format">image/jpeg</field><field name="size">123456</field><field name="url">https://directory.doabooks.org/bitstream/20.500.12854/12345/1/cover.jpg</field></element>
<element name="bitstream">
<field name="format">application/pdf</field><field name="oapenidentifierdownloadUrl">https://library.oapen.org/book.pdf</field>
<field name="rights">${rights}</field><field name="rightsuri">${rightsUri}</field>
</element></element></element></element></metadata></metadata></record>
<resumptionToken>next-page</resumptionToken></ListRecords></OAI-PMH>`;

describe("DOAB importer", () => {
  it("publishes engineering books with allowlisted per-file rights", () => {
    const page = parseDoabPage(
      xml("CC-BY", "https://creativecommons.org/licenses/by/4.0/"),
    );

    expect(page.sourceRecords).toBe(1);
    expect(page.nextToken).toBe("next-page");
    expect(page.revision).toHaveLength(64);
    expect(page.candidates[0]).toMatchObject({
      title: "Reliable Distributed Systems",
      author: "Ada Author",
      description: "A practical treatment of resilient distributed computing.",
      coverUrl:
        "https://directory.doabooks.org/bitstream/20.500.12854/12345/1/cover.jpg",
      coverAlt: "Official cover of Reliable Distributed Systems",
      category: "Computer Science",
      language: "en",
      licenseExpression: "CC-BY",
      verificationStatus: "VERIFIED",
      downloadUrl: "https://library.oapen.org/book.pdf",
    });
  });

  it("quarantines non-commercial books", () => {
    const page = parseDoabPage(
      xml("CC-BY-NC", "https://creativecommons.org/licenses/by-nc/4.0/"),
    );

    expect(page.candidates[0]).toMatchObject({
      licenseExpression: null,
      verificationStatus: "QUARANTINED",
      coverUrl: null,
      coverAlt: null,
    });
  });

  it("rejects entity-bearing XML", () => {
    expect(() =>
      parseDoabPage("<!DOCTYPE x [<!ENTITY y 'z'>]><x>&y;</x>"),
    ).toThrow("DOAB_UNSAFE_XML");
  });

  it("builds a bounded official OAI request", async () => {
    const fetcher = vi.fn(
      async () =>
        new Response(
          xml("CC0", "https://creativecommons.org/publicdomain/zero/1.0/"),
          {
            status: 200,
            headers: { "content-type": "text/xml;charset=UTF-8" },
          },
        ),
    );

    await expect(
      fetchDoabPage({ from: "2026-10-01", fetcher }),
    ).resolves.toMatchObject({
      sourceRecords: 1,
    });
    expect(fetcher.mock.calls[0]?.[0].toString()).toContain(
      "metadataPrefix=xoai",
    );
    expect(fetcher.mock.calls[0]?.[0].toString()).toContain("from=2026-10-01");
  });
});
