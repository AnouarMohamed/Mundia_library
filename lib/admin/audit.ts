import { db } from "@/database/drizzle";
import { auditLogs } from "@/database/schema";

/**
 * Log an admin action for auditing purposes
 * 
 * @param userId - ID of the admin performing the action
 * @param action - The action performed (e.g., "APPROVE_BORROW")
 * @param targetId - ID of the record affected
 * @param targetType - Type of the record affected (e.g., "BORROW_RECORD")
 * @param details - Optional additional context
 */
/**
 * Persist an admin audit log entry.
 */
export async function logAdminAction(
  userId: string,
  action: string,
  targetId?: string,
  targetType?: string,
  details?: Record<string, unknown>
) {
  try {
    await db.insert(auditLogs).values({
      userId,
      action,
      targetId,
      targetType,
      details: details ? JSON.stringify(details) : null,
    });
  } catch (error) {
    console.error("Failed to log admin action:", error);
    // We don't want to throw here to avoid breaking the main operation,
    // but in a production system we might want more robust error handling.
  }
}
