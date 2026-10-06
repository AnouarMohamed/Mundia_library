import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getLearningResource, isPublicHttpsUrl, searchLearningResources } from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("learning-resource BFF client", () => {
  it("accepts a bounded contract response", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
      resources: [resource()], total: 1, page: 0, totalPages: 1,
    }), { status: 200, headers: { "content-type": "application/json" } })));

    await expect(searchLearningResources({ query: "unix", category: "", page: 0 }))
      .resolves.toMatchObject({ total: 1, resources: [{ title: "Reliable UNIX Systems" }] });
  });

  it("rejects mismatched identities and unsafe URLs", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify(resource()), {
      status: 200, headers: { "content-type": "application/json" },
    })));

    await expect(getLearningResource("55555555-5555-4555-8555-555555555555"))
      .rejects.toBeInstanceOf(ApiError);
    expect(isPublicHttpsUrl("http://127.0.0.1/private")).toBe(false);
    expect(isPublicHttpsUrl("https://publisher.example.edu/book")).toBe(true);
  });
});

function resource() {
  return {
    resourceId: "44444444-4444-4444-8444-444444444444",
    title: "Reliable UNIX Systems",
    author: "Open Faculty",
    description: "Operational design for reliable UNIX services.",
    category: "Software Engineering",
    language: "en",
    coverUrl: null,
    coverAlt: null,
    sourceName: "University Open Press",
    sourceUrl: "https://publisher.example.edu/books/unix",
    licenseExpression: "CC-BY",
    licenseUrl: "https://creativecommons.org/licenses/by/4.0/",
    accessMode: "DOWNLOAD",
    readUrl: null,
  };
}
