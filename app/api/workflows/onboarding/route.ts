/**
 * User Onboarding Workflow
 * 
 * An automated background workflow managed by Upstash Workflow.
 * This workflow handles the post-signup experience for new users:
 * 1. Sends a welcome email immediately.
 * 2. Monitors user activity over time.
 * 3. Sends re-engagement or "welcome back" emails based on user state.
 * 4. Automatically terminates if the user account is deleted.
 * 
 * @module app/api/workflows/onboarding/route
 */

import { serve } from "@upstash/workflow/nextjs";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import { getWorkflowServeOptions, sendEmail } from "@/lib/workflow";

/**
 * Force Node.js runtime for database and workflow orchestration.
 */
export const runtime = "nodejs";

type UserState = "non-active" | "active";

/**
 * Initial payload expected when triggering the workflow.
 */
type InitialData = {
  email: string;
  fullName: string;
};

// Time constants for workflow scheduling.
const ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;
const THREE_DAYS_IN_MS = 3 * ONE_DAY_IN_MS;
const THIRTY_DAYS_IN_MS = 30 * ONE_DAY_IN_MS;

/**
 * Determines a user's activity state by checking their last activity timestamp in the database.
 * 
 * @param {string} email - The unique email of the user to check.
 * @returns {Promise<UserState>} "active" if recently active, "non-active" otherwise.
 */
const getUserState = async (email: string): Promise<UserState> => {
  try {
    const user = await db
      .select()
      .from(users)
      .where(eq(users.email, email))
      .limit(1);

    if (user.length === 0) return "non-active";

    // Check for recent activity.
    if (!user[0].lastActivityDate) {
      return "non-active";
    }

    const lastActivityDate = new Date(user[0].lastActivityDate);
    const now = new Date();
    const timeDifference = now.getTime() - lastActivityDate.getTime();

    // Users inactive for more than 3 days are considered "non-active".
    if (
      timeDifference > THREE_DAYS_IN_MS &&
      timeDifference <= THIRTY_DAYS_IN_MS
    ) {
      return "non-active";
    }

    return "active";
  } catch (error) {
    console.error("Error getting user state:", error);
    return "non-active";
  }
};

/**
 * Main Onboarding Workflow Definition.
 * 
 * Executed via Upstash's serverless workflow engine.
 * Uses `context.run` for durable, idempotent execution steps with automatic retries.
 */
export const { POST } = serve<InitialData>(
  async (context) => {
    const { email, fullName } = context.requestPayload;

    // Step 1: Send a welcome email immediately upon signup.
    await context.run("new-signup", async () => {
      await sendEmail({
        email,
        subject: "Welcome to Mundia Library",
        message: `Welcome ${fullName}! We're excited to have you on board. Explore our collection and start borrowing today.`,
      });
    });

    // Step 2: Wait for 3 days before starting the re-engagement loop.
    await context.sleep("wait-for-3-days", 60 * 60 * 24 * 3);

    // Step 3: Long-running engagement loop.
    while (true) {
      // Periodic state check.
      const state = await context.run("check-user-state", async () => {
        return await getUserState(email);
      });

      // Verification: Ensure the user still exists before proceeding.
      const userExists = await context.run("verify-user-exists", async () => {
        const user = await db.select({ id: users.id }).from(users).where(eq(users.email, email)).limit(1);
        return user.length > 0;
      });

      // Break loop and terminate workflow if user no longer exists.
      if (!userExists) break;

      if (state === "non-active") {
        // Re-engagement for inactive users.
        await context.run("send-email-non-active", async () => {
          await sendEmail({
            email,
            subject: "We miss you at the Library!",
            message: `Hey ${fullName}, it's been a few days since your last visit. Come check out our latest arrivals!`,
          });
        });
      } else if (state === "active") {
        // Encouragement for active users.
        await context.run("send-email-active", async () => {
          await sendEmail({
            email,
            subject: "Keep exploring the world of books!",
            message: `Hey ${fullName}, we love seeing you active! Happy reading!`,
          });
        });
      }

      // Step 4: Wait for 1 month before the next check.
      await context.sleep("wait-for-1-month", 60 * 60 * 24 * 30);
    }
  },
  getWorkflowServeOptions<InitialData>()
);
