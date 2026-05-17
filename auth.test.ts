import { beforeEach, describe, expect, it, vi } from "vitest";
import { hashLegacySha256Password } from "./lib/security/password";

const nextAuthConfig = vi.hoisted(() => ({ current: undefined as any }));
const selectRowsMock = vi.hoisted(() => vi.fn());
const updateSetMock = vi.hoisted(() => vi.fn());

vi.mock("next-auth", () => ({
  default: vi.fn((config) => {
    nextAuthConfig.current = config;
    return {
      handlers: {},
      signIn: vi.fn(),
      signOut: vi.fn(),
      auth: vi.fn(),
    };
  }),
}));

vi.mock("next-auth/providers/credentials", () => ({
  default: vi.fn((options) => options),
}));

vi.mock("@/database/drizzle", () => ({
  db: {
    select: vi.fn(() => ({
      from: vi.fn(() => ({
        where: vi.fn(() => ({
          limit: selectRowsMock,
        })),
      })),
    })),
    update: vi.fn(() => ({
      set: updateSetMock,
    })),
  },
}));

vi.mock("@/database/schema", () => ({
  users: {
    id: "id",
    email: "email",
    password: "password",
    role: "role",
    status: "status",
    universityId: "universityId",
    universityCard: "universityCard",
    lastLogin: "lastLogin",
  },
}));

vi.mock("drizzle-orm", () => ({
  eq: vi.fn((column, value) => ({ column, value })),
}));

vi.mock("@/lib/security/logger", () => ({
  logWarn: vi.fn(),
}));

describe("NextAuth credential authorization", () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    updateSetMock.mockReturnValue({
      where: vi.fn().mockResolvedValue([]),
    });
    await import("./auth");
  });

  it("lazily rewrites a valid legacy password to bcrypt", async () => {
    const legacyPassword = hashLegacySha256Password("valid-password");
    selectRowsMock.mockResolvedValue([
      {
        id: "user-1",
        email: "user@example.com",
        fullName: "Valid User",
        password: legacyPassword,
        role: "USER",
        status: "APPROVED",
        universityId: 12345678,
        universityCard: "card.jpg",
      },
    ]);

    const credentialsProvider = nextAuthConfig.current.providers[0];
    const user = await credentialsProvider.authorize({
      email: "USER@example.com",
      password: "valid-password",
    });

    expect(user).toMatchObject({
      id: "user-1",
      email: "user@example.com",
      status: "APPROVED",
    });
    expect(updateSetMock).toHaveBeenCalledWith(
      expect.objectContaining({
        password: expect.stringMatching(/^bcrypt:/),
      }),
    );
  });

  it("does not authorize pending accounts after password verification", async () => {
    selectRowsMock.mockResolvedValue([
      {
        id: "user-1",
        email: "user@example.com",
        fullName: "Pending User",
        password: hashLegacySha256Password("valid-password"),
        role: "USER",
        status: "PENDING",
        universityId: 12345678,
        universityCard: "card.jpg",
      },
    ]);

    const credentialsProvider = nextAuthConfig.current.providers[0];
    const user = await credentialsProvider.authorize({
      email: "user@example.com",
      password: "valid-password",
    });

    expect(user).toBeNull();
    expect(updateSetMock).not.toHaveBeenCalledWith(
      expect.objectContaining({
        password: expect.any(String),
      }),
    );
  });
});
