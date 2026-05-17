import { db } from "@/database/drizzle";
import { auditLogs } from "@/database/schema";
import { logError } from "@/lib/security/logger";

type AuditOptions = {
  mandatory?: boolean;
};

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
  details?: Record<string, unknown>,
  options: AuditOptions = {},
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
    logError("admin.audit_log_failed", error, {
      userId,
      action,
      targetId,
      targetType,
      mandatory: options.mandatory,
    });

    if (options.mandatory) {
      throw error;
    }
  }
}
