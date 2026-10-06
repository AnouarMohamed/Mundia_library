import { readFileSync } from "node:fs";
import vm from "node:vm";
import { describe, expect, it } from "vitest";

type EdgeRequest = {
  headers: { host: { value: string } };
  uri: string;
  querystring: Record<string, { value: string }>;
};

type EdgeResponse = {
  statusCode: number;
  headers: { location: { value: string } };
};

const template = readFileSync(
  "platform/terraform/modules/aws-web-edge/edge-router.js.tftpl",
  "utf8",
);
const code = template
  .replace("${redirect_hosts_json}", '["www.mundialibrary.tech"]')
  .replaceAll("${canonical_host}", "mundialibrary.tech");
const handler = vm.runInNewContext(`${code};handler`) as (event: {
  request: EdgeRequest;
}) => EdgeRequest | EdgeResponse;

function request(host: string, uri: string, querystring: EdgeRequest["querystring"] = {}) {
  return { request: { headers: { host: { value: host } }, uri, querystring } };
}

describe("CloudFront SPA edge router", () => {
  it("redirects www to the canonical apex while preserving path and query", () => {
    const response = handler(
      request("www.mundialibrary.tech", "/catalog", {
        q: { value: "cloud security" },
      }),
    ) as EdgeResponse;

    expect(response.statusCode).toBe(308);
    expect(response.headers.location.value).toBe(
      "https://mundialibrary.tech/catalog?q=cloud%20security",
    );
  });

  it("never rewrites BFF or OAuth routes to the SPA shell", () => {
    for (const uri of [
      "/api/v1/auth/session",
      "/oauth2/authorization/institutional",
      "/login/oauth2/code/institutional",
      "/error",
    ]) {
      expect((handler(request("mundialibrary.tech", uri)) as EdgeRequest).uri).toBe(uri);
    }
  });

  it("rewrites client-side routes but preserves fingerprinted assets", () => {
    expect(
      (handler(request("mundialibrary.tech", "/learning/resources")) as EdgeRequest).uri,
    ).toBe("/index.html");
    expect(
      (handler(request("mundialibrary.tech", "/assets/app-deadbeef.js")) as EdgeRequest).uri,
    ).toBe("/assets/app-deadbeef.js");
  });
});
