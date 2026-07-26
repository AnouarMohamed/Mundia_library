import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  requireAdminCapabilities,
  requireAdminCapability,
} from "./admin-capabilities";

const requireAdminMock = vi.hoisted(() => vi.fn());
const assignmentRowsMock = vi.hoisted(() => vi.fn());
const capabilityWhereMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/security/auth-guards", () => ({
  requireAdmin: requireAdminMock,
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(() => ({
      from: vi.fn(() => ({
        where: capabilityWhereMock,
      })),
    })),
  },
}));

vi.mock("@/lib/security/logger", () => ({
  logWarn: vi.fn(),
}));

const adminGuard = {
  ok: true as const,
  session: { user: { id: "admin-1" } },
  user: {
    id: "admin-1",
    role: "ADMIN" as const,
    status: "APPROVED" as const,
  },
};

describe("admin capability guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    requireAdminMock.mockResolvedValue(adminGuard);
    capabilityWhereMock.mockImplementation(assignmentRowsMock);
    assignmentRowsMock.mockResolvedValue([]);
  });

  it("preserves authentication and ADMIN denials without querying assignments", async () => {
    requireAdminMock.mockResolvedValueOnce({
      ok: false,
      status: 403,
      error: "Forbidden",
      message: "Admin access required",
    });

    await expect(
      requireAdminCapability("exports.read"),
    ).resolves.toMatchObject({
      ok: false,
      message: "Admin access required",
    });
    expect(capabilityWhereMock).not.toHaveBeenCalled();
  });

  it("denies an ADMIN with no active assignment", async () => {
    await expect(
      requireAdminCapability("identity_evidence.read"),
    ).resolves.toMatchObject({
      ok: false,
      status: 403,
      message: "Required administrative capability is not assigned",
    });
  });

  it("allows an ADMIN with the required active assignment", async () => {
    assignmentRowsMock.mockResolvedValueOnce([
      { capability: "identity_evidence.read" },
    ]);

    await expect(
      requireAdminCapability("identity_evidence.read"),
    ).resolves.toMatchObject({ ok: true });
  });

  it("requires every capability for separation of duties", async () => {
    assignmentRowsMock.mockResolvedValueOnce([
      { capability: "bulk.execute" },
    ]);

    await expect(
      requireAdminCapabilities([
        "bulk.execute",
        "roles.manage_admin",
      ]),
    ).resolves.toMatchObject({ ok: false, status: 403 });
  });

  it("does not fall back to ADMIN when the capability lookup fails", async () => {
    assignmentRowsMock.mockRejectedValueOnce(
      new Error("capability store unavailable"),
    );

    await expect(
      requireAdminCapability("exports.read"),
    ).rejects.toThrow("capability store unavailable");
  });
});
