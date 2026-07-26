import { describe, expect, it } from "vitest";

import { GET } from "./route";

describe("GET liveness", () => {
  it("reports only process liveness without dependency details", async () => {
    const response = await GET();

    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toEqual({ ok: true });
  });
});
