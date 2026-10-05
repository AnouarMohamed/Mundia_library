import { describe, expect, it, vi } from "vitest";
import { fetchOwaspCheatSheets, OWASP_LICENSE_EVIDENCE_URL } from "./owasp";

const index = Array.from(
  { length: 50 },
  (_, index) =>
    `<a href="cheatsheets/Topic_${index}_Cheat_Sheet.html">Topic ${index}</a>`,
).join("");

function response(body: string, type = "text/html") {
  return new Response(body, {
    status: 200,
    headers: { "content-type": type },
  });
}

describe("OWASP importer", () => {
  it("imports official indexed pages with verified metadata only", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      return response(
        url === OWASP_LICENSE_EVIDENCE_URL ? "License: CC BY-SA 4.0" : index,
      );
    });
    const batch = await fetchOwaspCheatSheets({ fetcher });

    expect(batch.revision).toMatch(/^[0-9a-f]{64}$/u);
    expect(batch.candidates).toHaveLength(50);
    expect(batch.candidates[0]).toMatchObject({
      licenseExpression: "CC-BY-SA",
      verificationStatus: "VERIFIED",
      downloadUrl: null,
      coverUrl: null,
    });
    expect(batch.candidates[0]?.sourceUrl).toMatch(
      /^https:\/\/cheatsheetseries\.owasp\.org\/cheatsheets\//u,
    );
  });

  it("fails closed when the official licence statement disappears", async () => {
    const fetcher = vi.fn(async (input: string | URL | Request) =>
      response(String(input) === OWASP_LICENSE_EVIDENCE_URL ? "" : index),
    );
    await expect(fetchOwaspCheatSheets({ fetcher })).rejects.toThrow(
      "OWASP_LICENSE_NOT_VERIFIED",
    );
  });

  it("rejects non-HTML responses", async () => {
    const fetcher = vi.fn(async () => response("{}", "application/json"));
    await expect(fetchOwaspCheatSheets({ fetcher })).rejects.toThrow(
      "OWASP_HTTP_200",
    );
  });
});
