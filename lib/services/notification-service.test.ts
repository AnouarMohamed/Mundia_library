import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  getUserNotifications,
  markAsRead,
  markAllAsRead,
} from "./notification-service";
import { db } from "@/database/drizzle";

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(),
    update: vi.fn(),
  },
}));

vi.mock("@/lib/security/logger", () => ({
  logError: vi.fn(),
}));

describe("notification service", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns true when a notification owned by the user is marked read", async () => {
    const returning = vi.fn().mockResolvedValue([{ id: "notification-1" }]);
    const where = vi.fn().mockReturnValue({ returning });
    const set = vi.fn().mockReturnValue({ where });

    vi.mocked(db.update).mockReturnValue({ set } as never);

    const result = await markAsRead("notification-1", "user-1");

    expect(result).toBe(true);
    expect(db.update).toHaveBeenCalledOnce();
    expect(set).toHaveBeenCalledWith({ isRead: true });
    expect(where).toHaveBeenCalledOnce();
    expect(returning).toHaveBeenCalledOnce();
  });

  it("returns false when no owned notification is updated", async () => {
    const returning = vi.fn().mockResolvedValue([]);
    const where = vi.fn().mockReturnValue({ returning });
    const set = vi.fn().mockReturnValue({ where });

    vi.mocked(db.update).mockReturnValue({ set } as never);

    await expect(markAsRead("notification-1", "user-2")).resolves.toBe(false);
  });

  it("clamps notification fetch limits", async () => {
    const limit = vi.fn().mockResolvedValue([]);
    const orderBy = vi.fn().mockReturnValue({ limit });
    const where = vi.fn().mockReturnValue({ orderBy });
    const from = vi.fn().mockReturnValue({ where });

    vi.mocked(db.select).mockReturnValue({ from } as never);

    await getUserNotifications("user-1", 500);

    expect(limit).toHaveBeenCalledWith(50);
  });

  it("returns true when marking all notifications completes", async () => {
    const where = vi.fn().mockResolvedValue(undefined);
    const set = vi.fn().mockReturnValue({ where });

    vi.mocked(db.update).mockReturnValue({ set } as never);

    await expect(markAllAsRead("user-1")).resolves.toBe(true);
    expect(where).toHaveBeenCalledOnce();
  });
});
