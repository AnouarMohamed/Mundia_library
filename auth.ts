/**
 * NextAuth Configuration for University Library Management System
 *
 * This file handles user authentication using NextAuth.js with:
 * - Credentials-based authentication (email/password)
 * - bcrypt password hashing with legacy SHA-256 verification
 * - JWT session strategy
 * - Lazy imports to support Edge runtime (middleware compatibility)
 *
 * IMPORTANT: This file uses lazy imports for database operations because:
 * - Next.js middleware runs in Edge runtime (doesn't support Node.js modules like 'pg')
 * - Database modules are only loaded when actually needed (in Node.js runtime contexts)
 * - This prevents "crypto module not supported" errors in Edge runtime
 */

import NextAuth, { User } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";
import {
  hashPassword,
  shouldRehashPassword,
  verifyPassword,
} from "@/lib/security/password";
import { logWarn } from "@/lib/security/logger";

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

/**
 * NextAuth configuration export
 * Provides: handlers (for API routes), signIn, signOut, and auth (for server components)
 */
export const { handlers, signIn, signOut, auth } = NextAuth({
  session: {
    strategy: "jwt", // Use JWT tokens instead of database sessions (faster, stateless)
  },
  providers: [
    /**
     * Credentials Provider - Email/Password Authentication
     *
     * Flow:
     * 1. User submits email/password
     * 2. Look up user in database by email
   * 3. Verify password using the current bcrypt hash or legacy SHA-256 fallback
     * 4. Return user object if valid, null if invalid
     */
    CredentialsProvider({
      async authorize(credentials) {
        // Validate input
        if (!credentials?.email || !credentials?.password) {
          return null;
        }

        /**
         * Lazy load database only when authorize is called (Node.js runtime)
         * This is safe because authorize() only runs in API routes (Node.js runtime)
         * Not in middleware (Edge runtime)
         */
        const db = await getDb();
        const users = await getUsersSchema();
        const eq = await getEq();

        // Query user by email
        const user = await db
          .select()
          .from(users)
          .where(eq(users.email, credentials.email.toString().toLowerCase()))
          .limit(1);

        if (user.length === 0) return null;

        const storedPassword = user[0].password;
        const password = credentials.password.toString();
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

        // Return user object for NextAuth (will be stored in JWT token)
        // CRITICAL: Include role/status for authorization checks
        return {
          id: user[0].id.toString(),
          email: user[0].email,
          name: user[0].fullName,
          role: user[0].role,
          status: user[0].status,
          universityId: user[0].universityId,
          universityCard: user[0].universityCard,
        } as User & {
          role: string;
          status: string;
          universityId: number;
          universityCard: string;
        };
      },
    }),
  ],
  pages: {
    signIn: "/sign-in",
  },
  callbacks: {
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
        token.id = user.id;
        token.name = user.name;
        // CRITICAL: Store role/status in JWT token for authorization checks
        token.role = (user as User & { role?: string }).role;
        token.status = (user as User & { status?: string }).status;
        token.universityId = (user as User & { universityId?: number })
          .universityId;
        token.universityCard = (user as User & { universityCard?: string })
          .universityCard;
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
        (session.user as {
          role?: string;
          status?: string;
          universityId?: number;
          universityCard?: string;
        }).role = token.role as string;
        (session.user as { status?: string }).status = token.status as string;
        (session.user as { universityId?: number }).universityId =
          token.universityId as number | undefined;
        (session.user as { universityCard?: string }).universityCard =
          token.universityCard as string | undefined;
      }

      return session;
    },
  },
});
