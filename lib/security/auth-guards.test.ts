import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  requireAdmin,
  requireApprovedUser,
  requireSelfOrAdmin,
  requireSuperAdmin,
  requireUser,
} from "./auth-guards";

const authMock = vi.hoisted(() => vi.fn());
const freshUserRowsMock = vi.hoisted(() => vi.fn());
const innerJoinMock = vi.hoisted(() => vi.fn());

vi.mock("@/auth", () => ({
  auth: authMock,
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(() => ({
      from: vi.fn(() => ({
        innerJoin: innerJoinMock,
        where: vi.fn(() => ({
          limit: freshUserRowsMock,
        })),
      })),
    })),
  },
}));

vi.mock("@/lib/security/logger", () => ({
  logWarn: vi.fn(),
}));

const approvedUser = {
  id: "user-1",
  email: "user@example.com",
  fullName: "Regular User",
  role: "USER",
  status: "APPROVED",
  universityId: 12345678,
  universityCard: "card.jpg",
};

const adminUser = {
  ...approvedUser,
  id: "admin-1",
  role: "ADMIN",
};

const superAdminUser = {
  ...approvedUser,
  id: "super-admin-1",
  role: "SUPER_ADMIN",
};

describe("auth guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authMock.mockResolvedValue({
      user: { id: "user-1" },
      authenticationMethod: "local-credentials",
    });
    innerJoinMock.mockReturnValue({
      where: vi.fn(() => ({
        limit: freshUserRowsMock,
      })),
    });
    freshUserRowsMock.mockResolvedValue([approvedUser]);
  });

  it("rejects unauthenticated requests", async () => {
    authMock.mockResolvedValue(null);

    const result = await requireUser();

    expect(result).toMatchObject({ ok: false, status: 401 });
  });

  it("rejects pending and rejected users from approved workflows", async () => {
    freshUserRowsMock.mockResolvedValueOnce([
      { ...approvedUser, status: "PENDING" },
    ]);

    const pending = await requireApprovedUser();

    expect(pending).toMatchObject({ ok: false, status: 403 });

    freshUserRowsMock.mockResolvedValueOnce([
      { ...approvedUser, status: "REJECTED" },
    ]);

    const rejected = await requireApprovedUser();

    expect(rejected).toMatchObject({ ok: false, status: 403 });
  });

  it("rejects non-admin users from admin workflows", async () => {
    const result = await requireAdmin();

    expect(result).toMatchObject({ ok: false, status: 403 });
  });

  it("allows super admins through admin and governance guards", async () => {
    freshUserRowsMock.mockResolvedValue([superAdminUser]);

    await expect(requireAdmin()).resolves.toMatchObject({ ok: true });
    await expect(requireSuperAdmin()).resolves.toMatchObject({ ok: true });
  });

  it("does not allow an operational admin through the super-admin guard", async () => {
    freshUserRowsMock.mockResolvedValue([adminUser]);

    await expect(requireSuperAdmin()).resolves.toMatchObject({
      ok: false,
      status: 403,
    });
  });

  it("allows admins and owners for self-or-admin checks", async () => {
    const owner = await requireSelfOrAdmin("user-1");

    expect(owner.ok).toBe(true);

    authMock.mockResolvedValueOnce({
      user: { id: "admin-1" },
      authenticationMethod: "local-credentials",
    });
    freshUserRowsMock.mockResolvedValueOnce([adminUser]);

    const admin = await requireSelfOrAdmin("user-2");

    expect(admin.ok).toBe(true);
  });

  it("rejects non-owner users for self-or-admin checks", async () => {
    const result = await requireSelfOrAdmin("user-2");

    expect(result).toMatchObject({ ok: false, status: 403 });
  });

  it("requires a live exact binding for institutional sessions", async () => {
    authMock.mockResolvedValue({
      user: { id: "user-1" },
      authenticationMethod: "institutional-oidc",
      federatedBindingId: "10000000-0000-4000-8000-000000000001",
    });

    await expect(requireUser()).resolves.toMatchObject({ ok: true });
    expect(innerJoinMock).toHaveBeenCalledOnce();

    freshUserRowsMock.mockResolvedValueOnce([]);
    await expect(requireUser()).resolves.toMatchObject({
      ok: false,
      status: 401,
    });
  });

  it("rejects institutional sessions missing their revocation binding", async () => {
    authMock.mockResolvedValue({
      user: { id: "user-1" },
      authenticationMethod: "institutional-oidc",
    });

    await expect(requireUser()).resolves.toMatchObject({
      ok: false,
      status: 401,
    });
  });

  it("rejects sessions issued before authentication-method binding", async () => {
    authMock.mockResolvedValue({ user: { id: "user-1" } });

    await expect(requireUser()).resolves.toMatchObject({
      ok: false,
      status: 401,
    });
  });
});
