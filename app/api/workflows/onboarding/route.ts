import { serve } from "@upstash/workflow/nextjs";
import { db } from "@/database/drizzle";
import { users } from "@/database/schema";
import { eq } from "drizzle-orm";
import { getWorkflowServeOptions, sendEmail } from "@/lib/workflow";

/**
 * Use Node.js runtime for DB access and workflow execution.
 */
export const runtime = "nodejs";

type UserState = "non-active" | "active";

type InitialData = {
  email: string;
  fullName: string;
};

const ONE_DAY_IN_MS = 24 * 60 * 60 * 1000;
const THREE_DAYS_IN_MS = 3 * ONE_DAY_IN_MS;
const THIRTY_DAYS_IN_MS = 30 * ONE_DAY_IN_MS;

/**
 * Determine whether a user is active based on last activity.
 */
const getUserState = async (email: string): Promise<UserState> => {
  try {
    const user = await db
      .select()
      .from(users)
      .where(eq(users.email, email))
      .limit(1);

    if (user.length === 0) return "non-active";

    // CRITICAL: Fix potential null/undefined error - check if lastActivityDate exists
    // If lastActivityDate is null or undefined, treat user as non-active
    if (!user[0].lastActivityDate) {
      return "non-active";
    }

    const lastActivityDate = new Date(user[0].lastActivityDate);
    const now = new Date();
    const timeDifference = now.getTime() - lastActivityDate.getTime();

    if (
      timeDifference > THREE_DAYS_IN_MS &&
      timeDifference <= THIRTY_DAYS_IN_MS
    ) {
      return "non-active";
    }

    return "active";
  } catch (error) {
    // If there's an error checking user state, default to non-active
    console.error("Error getting user state:", error);
    return "non-active";
  }
};

export const { POST } = serve<InitialData>(
  async (context) => {
    const { email, fullName } = context.requestPayload;

    // Welcome Email with Retries
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await (context as any).run("new-signup", {
      retries: 3,
      backoff: (retryCount: number) => Math.pow(2, retryCount) * 1000,
    }, async () => {
      await sendEmail({
        email,
        subject: "Welcome to the platform",
        message: `Welcome ${fullName}!`,
      });
    });

    await context.sleep("wait-for-3-days", 60 * 60 * 24 * 3);

    while (true) {
      const state = await context.run("check-user-state", async () => {
        return await getUserState(email);
      });

      // If user no longer exists, terminate workflow
      const userExists = await context.run("verify-user-exists", async () => {
        const user = await db.select({ id: users.id }).from(users).where(eq(users.email, email)).limit(1);
        return user.length > 0;
      });

      if (!userExists) break;

      if (state === "non-active") {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        await (context as any).run("send-email-non-active", { retries: 2 }, async () => {
          await sendEmail({
            email,
            subject: "Are you still there?",
            message: `Hey ${fullName}, we miss you!`,
          });
        });
      } else if (state === "active") {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        await (context as any).run("send-email-active", { retries: 2 }, async () => {
          await sendEmail({
            email,
            subject: "Welcome back!",
            message: `Welcome back ${fullName}!`,
          });
        });
      }

      await context.sleep("wait-for-1-month", 60 * 60 * 24 * 30);
    }
  },
  getWorkflowServeOptions<InitialData>()
);
