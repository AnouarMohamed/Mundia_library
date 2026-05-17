import { describe, expect, it } from "vitest";

import { isUuid, normalizeTextParam } from "./api-request";

describe("api request utilities", () => {
  it("validates UUID route params", () => {
    expect(isUuid("550e8400-e29b-41d4-a716-446655440000")).toBe(true);
    expect(isUuid("not-a-uuid")).toBe(false);
    expect(isUuid("550e8400-e29b-61d4-a716-446655440000")).toBe(false);
  });

  it("normalizes bounded text params", () => {
    expect(normalizeTextParam("  Mundiapolis  ", 6)).toBe("Mundia");
    expect(normalizeTextParam(null, 20)).toBe("");
  });
});
