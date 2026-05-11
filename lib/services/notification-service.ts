/**
 * Notification Service
 * 
 * This service handles the creation and management of user notifications.
 * It is designed to be a central hub for all notification logic, making it
 * easy to add new notification channels (e.g., Email, SMS, Web Push) in the future.
 * 
 * Current Capabilities:
 * - Persisting notifications to the database.
 * - Marking notifications as read.
 * - Fetching user notifications.
 */

import { db } from "@/database/drizzle";
import { notifications } from "@/database/schema";
import { eq, desc, and } from "drizzle-orm";

/**
 * Types of notifications supported by the system.
 */
export type NotificationType = "INFO" | "SUCCESS" | "WARNING" | "ERROR";

/**
 * Interface for creating a new notification.
 */
export interface CreateNotificationParams {
  userId: string;
  title: string;
  message: string;
  type?: NotificationType;
}

/**
 * Create and persist a new notification for a user.
 * 
 * @param params - The notification details (userId, title, message, type).
 * @returns A promise resolving to the created notification record or null on failure.
 */
export async function createNotification(params: CreateNotificationParams) {
  const { userId, title, message, type = "INFO" } = params;

  try {
    const [result] = await db.insert(notifications).values({
      userId,
      title,
      message,
      type,
      isRead: false,
    });

    return result;
  } catch (error) {
    console.error("Failed to create notification:", error);
    return null;
  }
}

/**
 * Fetch all notifications for a specific user, ordered by most recent first.
 * 
 * @param userId - The ID of the user whose notifications to fetch.
 * @param limit - Optional limit on the number of notifications to return.
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
 * Mark a specific notification as read.
 * 
 * @param notificationId - The ID of the notification to update.
 * @param userId - The ID of the user who must own the notification.
 * @returns A promise resolving to a boolean indicating success.
 */
export async function markAsRead(notificationId: string, userId: string) {
  try {
    const updatedRows = await db
      .update(notifications)
      .set({ isRead: true })
      .where(and(eq(notifications.id, notificationId), eq(notifications.userId, userId)))
      .returning({ id: notifications.id });

    return updatedRows.length > 0;
  } catch (error) {
    console.error("Error marking notification as read:", error);
    return false;
  }
}

/**
 * Mark all notifications as read for a specific user.
 * 
 * @param userId - The ID of the user whose notifications should be updated.
 * @returns A promise resolving to a boolean indicating success.
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
