import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getLearningResource, getMemberProfile, isPublicHttpsUrl, searchLearningResources } from "./client";

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

describe("member-profile BFF client", () => {
  it("accepts a caller-bound profile contract", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(profile())));

    await expect(getMemberProfile()).resolves.toMatchObject({
      fullName: "Houda Benjdida",
      role: "USER",
      status: "APPROVED",
    });
    expect(fetch).toHaveBeenCalledWith(
      "/api/v1/membership/profile",
      expect.objectContaining({ credentials: "same-origin" }),
    );
  });

  it.each([
    { fullName: "Unsafe\nName" },
    { universityId: 0 },
    { role: "ROOT" },
    { createdAt: "2026-10-07T00:00:00Z", updatedAt: "2026-10-06T00:00:00Z" },
  ])("rejects malformed profile data: %j", async (override) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse({ ...profile(), ...override })));

    await expect(getMemberProfile()).rejects.toBeInstanceOf(ApiError);
  });
});

function jsonResponse(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

function profile() {
  return {
    memberId: "77777777-7777-4777-8777-777777777777",
    email: "h.benjdida@mundiapolis.ma",
    fullName: "Houda Benjdida",
    universityId: 1,
    status: "APPROVED",
    role: "USER",
    createdAt: "2026-10-01T10:00:00Z",
    updatedAt: "2026-10-06T11:00:00Z",
  };
}

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
