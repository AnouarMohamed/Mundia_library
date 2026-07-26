import { beforeEach, describe, expect, it, vi } from "vitest";
import { db } from "@/database/drizzle";
import { approveRenewal, rejectRenewal } from "./renewal";

const mocks = vi.hoisted(() => ({
  requireAdmin: vi.fn(),
  logAdminAction: vi.fn(),
  createNotification: vi.fn(),
  revalidateCatalogTags: vi.fn(),
  logError: vi.fn(),
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    transaction: vi.fn(),
  },
}));

vi.mock("@/lib/security/auth-guards", () => ({
  requireAdmin: mocks.requireAdmin,
  guardToActionError: vi.fn((guard) => ({
    success: false,
    error: guard.message,
  })),
}));

vi.mock("@/lib/admin/audit", () => ({
  logAdminAction: mocks.logAdminAction,
}));

vi.mock("@/lib/cache/revalidate", () => ({
  revalidateCatalogTags: mocks.revalidateCatalogTags,
}));

vi.mock("@/lib/services/notification-service", () => ({
  createNotification: mocks.createNotification,
}));

vi.mock("@/lib/security/logger", () => ({
  logError: mocks.logError,
}));

const selectQuery = (rows: unknown[]) => {
  const query = {
    innerJoin: vi.fn(),
    where: vi.fn().mockReturnValue({
      limit: vi.fn().mockResolvedValue(rows),
    }),
  };
  query.innerJoin.mockReturnValue(query);

  return {
    from: vi.fn().mockReturnValue(query),
  };
};

const updateQuery = (rows: unknown[]) => ({
  set: vi.fn().mockReturnValue({
    where: vi.fn().mockReturnValue({
      returning: vi.fn().mockResolvedValue(rows),
    }),
  }),
});

describe("admin renewal decisions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.requireAdmin.mockResolvedValue({
      ok: true,
      user: { id: "admin-1", role: "ADMIN", status: "APPROVED" },
    });
  });

  it("atomically approves a pending renewal before emitting side effects", async () => {
    const tx = {
      select: vi
        .fn()
        .mockReturnValueOnce(
          selectQuery([
            {
              borrowRecordId: "borrow-1",
              userId: "user-1",
              status: "PENDING",
            },
          ]),
        )
        .mockReturnValueOnce(
          selectQuery([
            {
              dueDate: "2026-07-26",
              status: "BORROWED",
              bookTitle: "Distributed Systems",
            },
          ]),
        ),
      update: vi
        .fn()
        .mockReturnValueOnce(updateQuery([{ id: "renewal-1" }]))
        .mockReturnValueOnce(updateQuery([{ id: "borrow-1" }])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveRenewal("renewal-1");

    expect(result).toEqual({
      success: true,
      message: "Renewal approved and due date extended.",
    });
    expect(tx.update).toHaveBeenCalledTimes(2);
    expect(mocks.createNotification).toHaveBeenCalledTimes(1);
    expect(mocks.logAdminAction).toHaveBeenCalledTimes(1);
    expect(mocks.revalidateCatalogTags).toHaveBeenCalledTimes(1);
  });

  it("does not renew the loan when another decision wins the request transition", async () => {
    const tx = {
      select: vi
        .fn()
        .mockReturnValueOnce(
          selectQuery([
            {
              borrowRecordId: "borrow-1",
              userId: "user-1",
              status: "PENDING",
            },
          ]),
        )
        .mockReturnValueOnce(
          selectQuery([
            {
              dueDate: "2026-07-26",
              status: "BORROWED",
              bookTitle: "Distributed Systems",
            },
          ]),
        ),
      update: vi.fn().mockReturnValueOnce(updateQuery([])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveRenewal("renewal-1");

    expect(result).toEqual({
      success: false,
      error: "This renewal request has already been processed.",
    });
    expect(tx.update).toHaveBeenCalledTimes(1);
    expect(mocks.createNotification).not.toHaveBeenCalled();
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });

  it("fails safely when a concurrent return makes the loan inactive", async () => {
    const tx = {
      select: vi
        .fn()
        .mockReturnValueOnce(
          selectQuery([
            {
              borrowRecordId: "borrow-1",
              userId: "user-1",
              status: "PENDING",
            },
          ]),
        )
        .mockReturnValueOnce(
          selectQuery([
            {
              dueDate: "2026-07-26",
              status: "BORROWED",
              bookTitle: "Distributed Systems",
            },
          ]),
        ),
      update: vi
        .fn()
        .mockReturnValueOnce(updateQuery([{ id: "renewal-1" }]))
        .mockReturnValueOnce(updateQuery([])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await approveRenewal("renewal-1");

    expect(result).toEqual({
      success: false,
      error: "Renewals can only be approved for active borrowings.",
    });
    expect(mocks.createNotification).not.toHaveBeenCalled();
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });

  it("conditionally rejects a pending renewal", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            borrowRecordId: "borrow-1",
            userId: "user-1",
            status: "PENDING",
            bookTitle: "Distributed Systems",
          },
        ]),
      ),
      update: vi.fn().mockReturnValue(updateQuery([{ id: "renewal-1" }])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await rejectRenewal("renewal-1", "Limit reached");

    expect(result).toEqual({
      success: true,
      message: "Renewal request rejected.",
    });
    expect(mocks.createNotification).toHaveBeenCalledTimes(1);
    expect(mocks.logAdminAction).toHaveBeenCalledTimes(1);
  });

  it("does not emit rejection side effects after a lost race", async () => {
    const tx = {
      select: vi.fn().mockReturnValue(
        selectQuery([
          {
            borrowRecordId: "borrow-1",
            userId: "user-1",
            status: "PENDING",
            bookTitle: "Distributed Systems",
          },
        ]),
      ),
      update: vi.fn().mockReturnValue(updateQuery([])),
    };
    (db.transaction as any).mockImplementation(async (callback: any) =>
      callback(tx),
    );

    const result = await rejectRenewal("renewal-1");

    expect(result).toEqual({
      success: false,
      error: "This renewal request has already been processed.",
    });
    expect(mocks.createNotification).not.toHaveBeenCalled();
    expect(mocks.logAdminAction).not.toHaveBeenCalled();
  });
});
