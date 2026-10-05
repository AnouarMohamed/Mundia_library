import { describe, expect, it, vi } from "vitest";
import {
  fetchKubernetesGuides,
  KUBERNETES_GUIDES,
  KUBERNETES_LICENSE_EVIDENCE_URL,
} from "./kubernetes";

function response(body: string, type: string) {
  return new Response(body, { status: 200, headers: { "content-type": type } });
}

describe("Kubernetes learning-path importer", () => {
  it("verifies every official page and the repository licence", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) =>
      String(input) === KUBERNETES_LICENSE_EVIDENCE_URL
        ? response(
            "Creative Commons Attribution 4.0 International Public License",
            "text/plain",
          )
        : response("<html><title>Kubernetes</title></html>", "text/html"),
    );
    const batch = await fetchKubernetesGuides({ fetcher });

    expect(batch.candidates).toHaveLength(KUBERNETES_GUIDES.length);
    expect(
      batch.candidates.every((item) => item.verificationStatus === "VERIFIED"),
    ).toBe(true);
    expect(
      batch.candidates.every((item) => item.licenseExpression === "CC-BY"),
    ).toBe(true);
    expect(batch.candidates.every((item) => item.downloadUrl === null)).toBe(
      true,
    );
    expect(fetcher).toHaveBeenCalledTimes(KUBERNETES_GUIDES.length + 1);
  });

  it("fails closed when licence evidence changes", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) =>
      response(
        String(input) === KUBERNETES_LICENSE_EVIDENCE_URL
          ? "unknown"
          : "<html></html>",
        String(input) === KUBERNETES_LICENSE_EVIDENCE_URL
          ? "text/plain"
          : "text/html",
      ),
    );
    await expect(fetchKubernetesGuides({ fetcher })).rejects.toThrow(
      "KUBERNETES_LICENSE_NOT_VERIFIED",
    );
  });
});
