import { afterAll, beforeEach, describe, expect, it, vi } from "vitest";
import { hashLegacySha256Password } from "./lib/security/password";

const managedEnvironment = [
  "APP_ENV",
  "OIDC_ISSUER",
  "OIDC_CLIENT_ID",
  "OIDC_CLIENT_SECRET",
  "OIDC_ALLOWED_EMAIL_DOMAINS",
] as const;
const originalEnvironment = new Map(
  managedEnvironment.map((key) => [key, process.env[key]]),
);

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
  default: vi.fn((options) => ({
    id: "credentials",
    type: "credentials",
    ...options,
  })),
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
  logError: vi.fn(),
  logWarn: vi.fn(),
}));

vi.mock("@/lib/security/auth-rate-limit", () => ({
  allowCredentialAttempt: vi.fn().mockResolvedValue(true),
}));

describe("NextAuth credential authorization", () => {
  beforeEach(async () => {
    vi.clearAllMocks();
    process.env.APP_ENV = "test";
    process.env.OIDC_ISSUER = "https://identity.example.test/tenant";
    process.env.OIDC_CLIENT_ID = "library-bff";
    process.env.OIDC_CLIENT_SECRET = "unit-test-client-secret";
    process.env.OIDC_ALLOWED_EMAIL_DOMAINS = "example.com";
    updateSetMock.mockReturnValue({
      where: vi.fn().mockResolvedValue([]),
    });
    await import("./auth");
  });

  afterAll(() => {
    for (const key of managedEnvironment) {
      const original = originalEnvironment.get(key);
      if (original === undefined) delete process.env[key];
      else process.env[key] = original;
    }
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

    const credentialsProvider = nextAuthConfig.current.providers.find(
      (provider: { id?: string }) => provider.id === "credentials",
    );
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

    const credentialsProvider = nextAuthConfig.current.providers.find(
      (provider: { id?: string }) => provider.id === "credentials",
    );
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

  it("uses the provisioned local UUID instead of Auth.js's ephemeral OIDC UUID", async () => {
    const token = await nextAuthConfig.current.callbacks.jwt({
      token: { sub: "authjs-ephemeral-uuid" },
      user: {
        id: "authjs-ephemeral-uuid",
        localUserId: "00000000-0000-4000-8000-000000000001",
        name: "Institutional User",
        role: "USER",
        status: "APPROVED",
        universityId: 90000001,
        authenticationMethod: "institutional-oidc",
        federatedBindingId: "10000000-0000-4000-8000-000000000001",
      },
    });

    expect(token).toMatchObject({
      id: "00000000-0000-4000-8000-000000000001",
      sub: "00000000-0000-4000-8000-000000000001",
      role: "USER",
      status: "APPROVED",
      authenticationMethod: "institutional-oidc",
      federatedBindingId: "10000000-0000-4000-8000-000000000001",
    });
  });

  it("configures the institutional provider with all protocol checks", () => {
    const provider = nextAuthConfig.current.providers.find(
      (candidate: { id?: string }) => candidate.id === "institutional-oidc",
    );

    expect(provider).toMatchObject({
      type: "oidc",
      issuer: "https://identity.example.test/tenant",
      clientId: "library-bff",
      idToken: true,
      checks: ["pkce", "state", "nonce"],
      allowDangerousEmailAccountLinking: false,
      authorization: {
        params: {
          scope: "openid profile email",
          response_type: "code",
        },
      },
    });
  });

  it("bounds BFF sessions to eight hours", () => {
    expect(nextAuthConfig.current.session).toMatchObject({
      strategy: "jwt",
      maxAge: 8 * 60 * 60,
    });
  });
});
