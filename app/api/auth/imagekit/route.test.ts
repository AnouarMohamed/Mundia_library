import { describe, expect, it } from "vitest";

import { GET } from "./route";

describe("retired ImageKit signing route", () => {
  it("never issues a reusable client upload signature", async () => {
    const response = await GET();
    const body = await response.json();

    expect(response.status).toBe(410);
    expect(body).not.toHaveProperty("signature");
    expect(body).not.toHaveProperty("token");
  });
});
