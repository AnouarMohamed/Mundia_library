/**
 * Notification Service
 * 
 * This module provides a centralized API for managing in-app user notifications.
 * It serves as the primary interface for creating, retrieving, and updating the 
 * read status of notifications within the Mundia Library system.
 * 
 * Design Principles:
 * - Centralized logic: All notification persistence and state changes flow through this service.
 * - Extensibility: Structured to support future notification channels (e.g., Push, SMS).
 * - Security: Read operations are scoped by `userId` to prevent cross-user data leakage.
 * 
 * This service interacts directly with the `notifications` table in the database.
 */

import { db } from "@/database/drizzle";
import { notifications } from "@/database/schema";
import { eq, desc, and } from "drizzle-orm";

/**
 * Categorization for notifications to drive UI styling and severity levels.
 * - INFO: General updates or informational messages.
 * - SUCCESS: Confirmation of successful actions (e.g., book returned).
 * - WARNING: Urgent notices requiring attention (e.g., due date approaching).
 * - ERROR: Critical issues or failures (e.g., payment failure).
 */
export type NotificationType = "INFO" | "SUCCESS" | "WARNING" | "ERROR";

/**
 * Data required to create a new notification.
 */
export interface CreateNotificationParams {
  /** The unique identifier of the recipient user. */
  userId: string;
  /** Short, descriptive title of the notification. */
  title: string;
  /** Detailed content of the notification message. */
  message: string;
  /** Optional severity type (defaults to INFO). */
  type?: NotificationType;
}

/**
 * Creates and persists a new notification record in the database.
 * 
 * @param params - The notification details (userId, title, message, type).
 * @returns A promise resolving to the database insertion result or null on failure.
 */
export async function createNotification(params: CreateNotificationParams) {
  const { userId, title, message, type = "INFO" } = params;

  try {
    const result = await db.insert(notifications).values({
      userId,
      title,
      message,
      type,
      isRead: false,
    });

    return result;
  } catch (error) {
    // We catch and log here to prevent notification failures from crashing the caller's main transaction
    console.error("Failed to create notification:", error);
    return null;
  }
}

/**
 * Fetches a list of notifications for a specific user.
 * Results are sorted in reverse chronological order (newest first).
 * 
 * @param userId - The ID of the user whose notifications to fetch.
 * @param limit - Maximum number of notifications to retrieve (defaults to 20).
 * @returns A promise resolving to an array of notification objects.
 */
export async function getUserNotifications(userId: string, limit: number = 20) {
  try {
    return await db
      .select()
      .from(notifications)
      .where(eq(notifications.userId, userId))
      .orderBy(desc(notifications.createdAt))
      .limit(limit);
  } catch (error) {
    console.error("Error fetching user notifications:", error);
    return [];
  }
}

/**
 * Updates the status of a specific notification to 'read'.
 * Verification of user ownership is enforced to ensure security.
 * 
 * @param notificationId - The unique identifier of the notification to update.
 * @param userId - The ID of the user performing the action (must own the notification).
 * @returns A promise resolving to a boolean indicating if the update was successful.
 */
export async function markAsRead(notificationId: string, userId: string) {
  try {
    const result = await db
      .update(notifications)
      .set({ isRead: true })
      .where(and(eq(notifications.id, notificationId), eq(notifications.userId, userId)));

    // Handle database-specific result objects (e.g., MySQL affectedRows)
    if (typeof result === "object" && result !== null && "affectedRows" in result) {
      const { affectedRows } = result as { affectedRows: number };
      return affectedRows > 0;
    }
    return false;
  } catch (error) {
    console.error("Error marking notification as read:", error);
    return false;
  }
}

/**
 * Marks all unread notifications for a specific user as 'read'.
 * Useful for "Mark all as read" UI functionality.
 * 
 * @param userId - The ID of the user whose notifications should be updated.
 * @returns A promise resolving to a boolean indicating if the operation was successful.
 */
export async function markAllAsRead(userId: string) {
  try {
    await db
      .update(notifications)
      .set({ isRead: true })
      .where(and(eq(notifications.userId, userId), eq(notifications.isRead, false)));
    return true;
  } catch (error) {
    console.error("Error marking all notifications as read:", error);
    return false;
  }
}
