/**
 * Authentication Server Actions
 * 
 * This module provides server-side logic for student authentication and registration.
 * It integrates with NextAuth for session management, handles secure password hashing,
 * implements rate limiting to prevent abuse, and manages onboarding workflows.
 * 
 * Key Features:
 * - Credentials-based sign in.
 * - Secure student registration with bcrypt password hashing.
 * - Integration with Upstash QStash for background onboarding workflows.
 * - Robust error handling including transient database error retries and field-specific validation.
 */

"use server";

import { eq } from "drizzle-orm";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { signIn } from "@/auth";
import { headers } from "next/headers";
import ratelimit from "@/lib/ratelimit";
import { redirect } from "next/navigation";
import { workflowClient } from "@/lib/workflow";
import config from "@/lib/config";
import { isTransientDbError, withDbRetry } from "@/lib/db/retry";
import { hashPassword } from "@/lib/security/password";
import { logError, logInfo } from "@/lib/security/logger";

/**
 * Authenticates a user using email and password credentials.
 * 
 * This action handles the standard sign-in flow, including rate limiting
 * based on the requester's IP address.
 * 
 * @param params - Object containing user email and password.
 * @returns A promise resolving to a success flag and an optional error message.
 */
export const signInWithCredentials = async (
  params: Pick<AuthCredentials, "email" | "password">
) => {
  const { email, password } = params;
  const normalizedEmail = email.toLowerCase();

  // Identify client IP for rate limiting
  const ip = (await headers()).get("x-forwarded-for") || "127.0.0.1";
  const { success } = await ratelimit.limit(ip);

  if (!success) return redirect("/too-fast");

  try {
    const result = await signIn("credentials", {
      email: normalizedEmail,
      password,
      redirect: false,
    });

    if (result?.error) {
      return { success: false, error: result.error };
    }

    return { success: true };
  } catch (error) {
    logError("auth.signin_failed", error, { email: normalizedEmail });
    return {
      success: false,
      error:
        "Sign in failed. Confirm your account is approved and your credentials are correct.",
    };
  }
};

/**
 * Registers a new student account in the system.
 * 
 * Flow:
 * 1. Validates the University ID (must be a positive 8-digit integer).
 * 2. Hashes the password using bcrypt.
 * 3. Checks for duplicate emails or University IDs in the database.
 * 4. Inserts the new user record with transient error retries.
 * 5. Triggers an onboarding workflow if enabled.
 * 6. Leaves the account pending until admin approval.
 * 
 * @param params - The full registration data for the student.
 * @returns A promise resolving to a success flag, or an error object with field-specific feedback.
 */
export const signUp = async (params: AuthCredentials) => {
  const { fullName, email, universityId, password, universityCard } = params;
  const normalizedEmail = email.toLowerCase();

  // Apply rate limiting based on IP address
  const ip = (await headers()).get("x-forwarded-for") || "127.0.0.1";
  const { success } = await ratelimit.limit(ip);

  if (!success) return redirect("/too-fast");

  // Validate universityId is within PostgreSQL integer range
  const MAX_INTEGER = 2147483647;
  const MAX_8_DIGIT = 99999999;

  // Validate universityId is a whole number (integer)
  if (!Number.isInteger(universityId)) {
    return {
      success: false,
      error: "universityId",
      fieldError: "University ID must be a whole number (no decimals).",
    };
  }

  // Validate universityId is positive
  if (universityId < 1) {
    return {
      success: false,
      error: "universityId",
      fieldError: "University ID must be a positive number.",
    };
  }

  // Validate universityId is within 8-digit range
  if (universityId > MAX_8_DIGIT) {
    return {
      success: false,
      error: "universityId",
      fieldError: "University ID is too large. Maximum allowed 8-digit number.",
    };
  }

  // Validate universityId is within PostgreSQL integer range (safety check)
  if (universityId > MAX_INTEGER) {
    return {
      success: false,
      error: "universityId",
      fieldError: "University ID is too large. Maximum allowed 8-digit number.",
    };
  }

  const hashedPassword = await hashPassword(password);

  try {
    // Check for existing users with the same email or ID
    const [existingUser, existingUniversityId] = await withDbRetry(
      () =>
        Promise.all([
          db
            .select()
            .from(users)
            .where(eq(users.email, normalizedEmail))
            .limit(1),
          db
            .select()
            .from(users)
            .where(eq(users.universityId, universityId))
            .limit(1),
        ]),
      { retries: 2, delayMs: 300 }
    );

    if (existingUser.length > 0) {
      return {
        success: false,
        error: "email",
        fieldError:
          "This email is already registered. Please use a different email or sign in.",
      };
    }

    if (existingUniversityId.length > 0) {
      return {
        success: false,
        error: "universityId",
        fieldError:
          "This University ID is already registered. Please use a different ID or contact support if this is your ID.",
      };
    }

    // Insert new user into the database
    await withDbRetry(
      () =>
        db.insert(users).values({
          fullName,
          email: normalizedEmail,
          universityId,
          password: hashedPassword,
          universityCard,
        }),
      { retries: 2, delayMs: 300 }
    );

    const workflowsEnabled =
      process.env.ENABLE_WORKFLOWS === "true" ||
      (process.env.NODE_ENV === "production" &&
        process.env.ENABLE_WORKFLOWS !== "false");

    // Only trigger workflow when real QStash credentials are expected.
    if (workflowsEnabled) {
      await workflowClient.trigger({
        url: `${config.env.prodApiEndpoint}/api/workflows/onboarding`,
        body: {
          email: normalizedEmail,
          fullName,
        },
      });
    } else {
      console.log("Skipping workflow trigger in development mode");
    }

    logInfo("auth.signup_pending_account_created", {
      email: normalizedEmail,
    });

    return { success: true };
  } catch (error) {
    // Handle PostgreSQL integer range errors
    if (
      error instanceof Error &&
      (error.message.includes("out of range") ||
        error.message.includes("integer") ||
        error.message.includes("22003"))
    ) {
      return {
        success: false,
        error: "universityId",
        fieldError: "University ID is too large. Maximum allowed 8-digit number.",
      };
    }

    // Handle transient database errors
    if (isTransientDbError(error)) {
      return {
        success: false,
        error:
          "Signup service is temporarily unavailable. Please try again in a moment.",
      };
    }

    // Handle database-level unique constraint violations
    if (
      error instanceof Error &&
      (error.message.includes("unique") ||
        error.message.includes("duplicate") ||
        error.message.includes("23505"))
    ) {
      if (error.message.includes("email")) {
        return {
          success: false,
          error: "email",
          fieldError: "This email is already registered. Please use a different email or sign in.",
        };
      } else if (error.message.includes("university_id")) {
        return {
          success: false,
          error: "universityId",
          fieldError: "This University ID is already registered. Please use a different ID or contact support if this is your ID.",
        };
      }
    }

    logError("auth.signup_failed", error, { email: normalizedEmail });
    return {
      success: false,
      error: "Signup error. Please check your information and try again.",
    };
  }
};
