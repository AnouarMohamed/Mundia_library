import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  requireAdmin,
  requireApprovedUser,
  requireSelfOrAdmin,
  requireUser,
} from "./auth-guards";

const authMock = vi.hoisted(() => vi.fn());
const freshUserRowsMock = vi.hoisted(() => vi.fn());

vi.mock("@/auth", () => ({
  auth: authMock,
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(() => ({
      from: vi.fn(() => ({
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

describe("auth guards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authMock.mockResolvedValue({ user: { id: "user-1" } });
    freshUserRowsMock.mockResolvedValue([approvedUser]);
  });

  it("rejects unauthenticated requests", async () => {
    authMock.mockResolvedValue(null);

    const result = await requireUser();

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.status).toBe(401);
    }
  });

  it("rejects pending and rejected users from approved workflows", async () => {
    freshUserRowsMock.mockResolvedValueOnce([
      { ...approvedUser, status: "PENDING" },
    ]);

    const pending = await requireApprovedUser();

    expect(pending.ok).toBe(false);
    if (!pending.ok) {
      expect(pending.status).toBe(403);
    }

    freshUserRowsMock.mockResolvedValueOnce([
      { ...approvedUser, status: "REJECTED" },
    ]);

    const rejected = await requireApprovedUser();

    expect(rejected.ok).toBe(false);
    if (!rejected.ok) {
      expect(rejected.status).toBe(403);
    }
  });

  it("rejects non-admin users from admin workflows", async () => {
    const result = await requireAdmin();

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.status).toBe(403);
    }
  });

  it("allows admins and owners for self-or-admin checks", async () => {
    const owner = await requireSelfOrAdmin("user-1");

    expect(owner.ok).toBe(true);

    authMock.mockResolvedValueOnce({ user: { id: "admin-1" } });
    freshUserRowsMock.mockResolvedValueOnce([adminUser]);

    const admin = await requireSelfOrAdmin("user-2");

    expect(admin.ok).toBe(true);
  });

  it("rejects non-owner users for self-or-admin checks", async () => {
    const result = await requireSelfOrAdmin("user-2");

    expect(result.ok).toBe(false);
    if (!result.ok) {
      expect(result.status).toBe(403);
    }
  });
});
