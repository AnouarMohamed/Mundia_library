/**
 * NextAuth Configuration for University Library Management System
 *
 * This file handles user authentication using NextAuth.js with:
 * - Institutional OIDC authorization-code authentication
 * - Local-only credentials compatibility for development and tests
 * - bcrypt password hashing with legacy SHA-256 verification
 * - JWT session strategy
 * - Lazy imports to support Edge runtime (middleware compatibility)
 *
 * IMPORTANT: This file uses lazy imports for database operations because:
 * - Next.js middleware runs in Edge runtime (doesn't support Node.js modules like 'pg')
 * - Database modules are only loaded when actually needed (in Node.js runtime contexts)
 * - This prevents "crypto module not supported" errors in Edge runtime
 */

import NextAuth, { type NextAuthConfig, type User } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";
import config from "@/lib/config";
import {
  hashPassword,
  shouldRehashPassword,
  verifyPassword,
} from "@/lib/security/password";
import { logWarn } from "@/lib/security/logger";
import {
  FederatedIdentityRejectedError,
  resolveInstitutionalUser,
  type InstitutionalOidcProfile,
} from "@/lib/security/oidc-identity";

const DUMMY_PASSWORD_HASH =
  "bcrypt:$2a$12$MfiHJ9FzN45.FN6ibQBlFuH9YqrTr2Vw5J/AEmgYVSHTtjkOIVNKe";
export const INSTITUTIONAL_OIDC_PROVIDER_ID = "institutional-oidc";
export const AUTH_SESSION_MAX_AGE_SECONDS = 8 * 60 * 60;

type AppAuthUser = User & {
  role?: string;
  status?: string;
  universityId?: number;
  institutionalIdentityAuthorized?: boolean;
  localUserId?: string;
  authenticationMethod?: "institutional-oidc" | "local-credentials";
  federatedBindingId?: string;
};

/**
 * Lazy import pattern for database connection
 *
 * WHY LAZY IMPORTS?
 * - This file is imported by middleware.ts which runs in Edge runtime
 * - Edge runtime doesn't support Node.js modules (like 'pg' for PostgreSQL)
 * - By using dynamic imports, we only load the database when actually needed
 * - Database operations only happen in Node.js runtime (authorize/jwt callbacks)
 *
 * This prevents: "The edge runtime does not support Node.js 'crypto' module" errors
 */
async function getDb() {
  const { db } = await import("@/database/drizzle");
  return db;
}

async function getUsersSchema() {
  const { users } = await import("@/database/schema");
  return users;
}

async function getEq() {
  const { eq } = await import("drizzle-orm");
  return eq;
}

const credentialsProvider = CredentialsProvider({
  async authorize(credentials) {
    if (!config.env.localCredentialsEnabled) {
      logWarn("auth.local_credentials_disabled");
      return null;
    }

    if (!credentials?.email || !credentials?.password) {
      return null;
    }

    const email = credentials.email.toString().trim().toLowerCase();
    const password = credentials.password.toString();
    if (email.length > 254 || password.length === 0 || password.length > 128) {
      return null;
    }

    const { allowCredentialAttempt } =
      await import("@/lib/security/auth-rate-limit");
    if (!(await allowCredentialAttempt(email))) {
      logWarn("auth.credential_attempt_limited");
      return null;
    }

    const db = await getDb();
    const users = await getUsersSchema();
    const eq = await getEq();

    const user = await db
      .select()
      .from(users)
      .where(eq(users.email, email))
      .limit(1);

    if (user.length === 0) {
      await verifyPassword(password, DUMMY_PASSWORD_HASH);
      return null;
    }

    const storedPassword = user[0].password;
    const isPasswordValid = await verifyPassword(password, storedPassword);

    if (!isPasswordValid) return null;

    if (user[0].status !== "APPROVED") {
      logWarn("auth.signin_unapproved_account", {
        userId: user[0].id,
        status: user[0].status,
      });
      return null;
    }

    if (shouldRehashPassword(storedPassword)) {
      await db
        .update(users)
        .set({ password: await hashPassword(password) })
        .where(eq(users.id, user[0].id));
    }

    await db
      .update(users)
      .set({ lastLogin: new Date() })
      .where(eq(users.id, user[0].id));

    return {
      id: user[0].id.toString(),
      email: user[0].email,
      name: user[0].fullName,
      role: user[0].role,
      status: user[0].status,
      universityId: user[0].universityId,
      authenticationMethod: "local-credentials",
    } satisfies AppAuthUser;
  },
});

const institutionalOidcProvider: NextAuthConfig["providers"][number] = {
  id: INSTITUTIONAL_OIDC_PROVIDER_ID,
  name: "Mundiapolis institutional account",
  type: "oidc",
  issuer: config.env.oidc.issuer,
  clientId: config.env.oidc.clientId,
  clientSecret: config.env.oidc.clientSecret,
  idToken: true,
  authorization: {
    params: {
      scope: "openid profile email",
      response_type: "code",
    },
  },
  checks: ["pkce", "state", "nonce"],
  allowDangerousEmailAccountLinking: false,
  async profile(profile: InstitutionalOidcProfile) {
    try {
      const localUser = await resolveInstitutionalUser(profile, {
        issuer: config.env.oidc.issuer,
        allowedEmailDomains: config.env.oidc.allowedEmailDomains,
      });

      return {
        ...localUser,
        // Auth.js keeps this value as the ephemeral providerAccountId, then
        // intentionally replaces `user.id` for adapter-independent OAuth.
        // Carry the authorized local UUID separately for the JWT callback.
        id: profile.sub as string,
        localUserId: localUser.id,
        authenticationMethod: "institutional-oidc",
        federatedBindingId: localUser.federatedBindingId,
        institutionalIdentityAuthorized: true,
      } satisfies AppAuthUser;
    } catch (error) {
      if (error instanceof FederatedIdentityRejectedError) {
        logWarn("auth.institutional_identity_rejected", {
          reason: error.reason,
        });
        return {
          id: "institutional-identity-denied",
          institutionalIdentityAuthorized: false,
        } satisfies AppAuthUser;
      }

      throw error;
    }
  },
};

const providers: NextAuthConfig["providers"] = [];
if (config.env.oidc.enabled) providers.push(institutionalOidcProvider);
if (config.env.localCredentialsEnabled) providers.push(credentialsProvider);

/**
 * NextAuth configuration export
 * Provides: handlers (for API routes), signIn, signOut, and auth (for server components)
 */
export const { handlers, signIn, signOut, auth } = NextAuth({
  session: {
    strategy: "jwt", // Use JWT tokens instead of database sessions (faster, stateless)
    maxAge: AUTH_SESSION_MAX_AGE_SECONDS,
  },
  providers,
  pages: {
    signIn: "/sign-in",
  },
  callbacks: {
    async signIn({ account, user }) {
      if (account?.provider === INSTITUTIONAL_OIDC_PROVIDER_ID) {
        return (user as AppAuthUser).institutionalIdentityAuthorized === true;
      }

      if (account?.provider === "credentials") {
        return config.env.localCredentialsEnabled;
      }

      return false;
    },
    /**
     * JWT Callback - Called when JWT token is created or updated
     *
     * This runs in Node.js runtime (API routes), so database access is safe
     *
     * Flow:
     * 1. When user signs in, 'user' object is provided
     * 2. Store user.id and user.name in JWT token
     * 3. Return token (will be sent to client as cookie)
     */
    async jwt({ token, user }) {
      // Only runs on initial sign-in (when 'user' is provided)
      if (user) {
        // Store user data in JWT token
        const localUserId = (user as AppAuthUser).localUserId ?? user.id;
        token.id = localUserId;
        token.sub = localUserId;
        token.name = user.name;
        // CRITICAL: Store role/status in JWT token for authorization checks
        token.role = (user as AppAuthUser).role;
        token.status = (user as AppAuthUser).status;
        token.universityId = (user as AppAuthUser).universityId;
        token.authenticationMethod = (
          user as AppAuthUser
        ).authenticationMethod;
        token.federatedBindingId = (
          user as AppAuthUser
        ).federatedBindingId;
      }

      return token;
    },
    /**
     * Session Callback - Called whenever session is accessed
     *
     * This transforms the JWT token into the session object
     * that's available in Server Components via auth()
     *
     * Flow:
     * 1. Extract data from JWT token
     * 2. Add to session.user object
     * 3. Return session (available in getServerSession(), auth(), etc.)
     */
    async session({ session, token }) {
      if (session.user) {
        // Add user ID and name from JWT token to session
        session.user.id = token.id as string;
        session.user.name = token.name as string;
        // CRITICAL: Add role/status to session for authorization checks
        // Type assertion needed because NextAuth types don't include role by default
        (
          session.user as {
            role?: string;
            status?: string;
            universityId?: number;
          }
        ).role = token.role as string;
        (session.user as { status?: string }).status = token.status as string;
        (session.user as { universityId?: number }).universityId =
          token.universityId as number | undefined;
        (
          session as {
            authenticationMethod?: string;
            federatedBindingId?: string;
          }
        ).authenticationMethod = token.authenticationMethod as
          | "institutional-oidc"
          | "local-credentials"
          | undefined;
        (
          session as {
            authenticationMethod?: string;
            federatedBindingId?: string;
          }
        ).federatedBindingId = token.federatedBindingId as
          | string
          | undefined;
      }

      return session;
    },
  },
});
