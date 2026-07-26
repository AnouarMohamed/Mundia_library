import { describe, expect, it } from "vitest";

import { enforceSameOriginRequest } from "./same-origin";

const createRequest = ({
  method = "POST",
  origin,
  contentType,
  fetchSite,
  url = "https://library.example/api/notifications",
}: {
  method?: string;
  origin?: string;
  contentType?: string;
  fetchSite?: string;
  url?: string;
} = {}) => {
  const headers = new Headers();

  if (origin !== undefined) headers.set("Origin", origin);
  if (contentType !== undefined) headers.set("Content-Type", contentType);
  if (fetchSite !== undefined) headers.set("Sec-Fetch-Site", fetchSite);

  return new Request(url, { method, headers });
};

describe("same-origin request guard", () => {
  it.each(["GET", "HEAD", "OPTIONS"])(
    "does not constrain safe %s requests",
    (method) => {
      const response = enforceSameOriginRequest(createRequest({ method }), {
        requireJson: true,
      });

      expect(response).toBeNull();
    },
  );

  it("accepts an exact same-origin JSON mutation", () => {
    const response = enforceSameOriginRequest(
      createRequest({
        origin: "https://library.example",
        contentType: "application/json; charset=utf-8",
        fetchSite: "same-origin",
      }),
      { requireJson: true },
    );

    expect(response).toBeNull();
  });

  it("accepts structured JSON suffix media types", () => {
    const response = enforceSameOriginRequest(
      createRequest({
        origin: "https://library.example",
        contentType: "application/merge-patch+json",
      }),
      { requireJson: true },
    );

    expect(response).toBeNull();
  });

  it.each([
    ["a missing Origin header", undefined],
    ["an opaque Origin header", "null"],
    ["a cross-origin host", "https://attacker.example"],
    ["a same-site sibling origin", "https://admin.library.example"],
    ["a different scheme", "http://library.example"],
    ["a different port", "https://library.example:444"],
    ["an Origin value containing a path", "https://library.example/path"],
    ["an Origin value containing a trailing slash", "https://library.example/"],
  ])("rejects %s", async (_label, origin) => {
    const response = enforceSameOriginRequest(
      createRequest({
        origin,
        contentType: "application/json",
      }),
      { requireJson: true },
    );

    expect(response?.status).toBe(403);
    await expect(response?.json()).resolves.toMatchObject({
      success: false,
      error: "Forbidden",
      message: "Cross-site request rejected",
    });
  });

  it("rejects contradictory cross-site Fetch Metadata", () => {
    const response = enforceSameOriginRequest(
      createRequest({
        origin: "https://library.example",
        contentType: "application/json",
        fetchSite: "cross-site",
      }),
      { requireJson: true },
    );

    expect(response?.status).toBe(403);
  });

  it("rejects simple-form media types on JSON endpoints", async () => {
    const response = enforceSameOriginRequest(
      createRequest({
        origin: "https://library.example",
        contentType: "text/plain",
      }),
      { requireJson: true },
    );

    expect(response?.status).toBe(415);
    await expect(response?.json()).resolves.toMatchObject({
      success: false,
      error: "Unsupported Media Type",
      message: "Content-Type must be application/json",
    });
  });

  it("allows body-less same-origin mutations when JSON is not required", () => {
    const response = enforceSameOriginRequest(
      createRequest({
        method: "PATCH",
        origin: "https://library.example",
      }),
    );

    expect(response).toBeNull();
  });
});
