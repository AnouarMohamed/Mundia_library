import { describe, expect, it } from "vitest";

import { adminUserColumns } from "@/lib/admin/user-projection";

describe("admin user serialization policy", () => {
  it("never includes authentication secrets in browser-bound projections", () => {
    expect(Object.keys(adminUserColumns)).not.toContain("password");
    expect(Object.keys(adminUserColumns)).not.toContain("passwordHash");
    expect(Object.keys(adminUserColumns)).not.toContain("token");
    expect(Object.keys(adminUserColumns)).not.toContain("universityCard");
  });
});
