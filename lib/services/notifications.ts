/**
 * Client-Side Notification Service
 * 
 * This module provides functions for interacting with the notification system from the client.
 * It strictly uses the `fetch` API to communicate with the project's internal API routes.
 * 
 * Features:
 * - Fetching current user's notifications.
 * - Marking individual notifications as read.
 * - Marking all notifications as read in bulk.
 * 
 * Note: These functions are designed for use in Client Components and custom hooks.
 */

/**
 * Standard notification structure returned by the API.
 */
export interface Notification {
  /** Unique identifier for the notification record. */
  id: string;
  /** ID of the user who owns this notification. */
  userId: string;
  /** Brief summary of the notification. */
  title: string;
  /** Detailed content of the notification message. */
  message: string;
  /** Categorization for UI styling (e.g., color-coding). */
  type: "INFO" | "SUCCESS" | "WARNING" | "ERROR";
  /** Indicates if the user has seen/acknowledged the notification. */
  isRead: boolean;
  /** Timestamp of when the notification was generated. */
  createdAt: Date | null;
}

/**
 * Fetches a list of notifications for the authenticated user.
 * 
 * @param _userId - User ID parameter (currently unused as the API identifies the user via session).
 * @returns A promise resolving to an array of Notification objects.
 * @throws Error if the API request fails.
 */
export async function getNotifications(_userId: string): Promise<Notification[]> {
  const response = await fetch("/api/notifications");
  
  if (!response.ok) {
    throw new Error("Failed to fetch notifications");
  }

  const data = await response.json();
  return data.notifications || [];
}

/**
 * Marks a specific notification as 'read' via a PATCH request.
 * 
 * @param notificationId - Unique identifier of the notification to update.
 * @returns A promise resolving to true if the operation succeeded, false otherwise.
 */
export async function setRead(notificationId: string): Promise<boolean> {
  const response = await fetch(`/api/notifications/${notificationId}`, {
    method: "PATCH",
  });
  
  if (!response.ok) {
    return false;
  }

  const data = await response.json();
  return data.success === true;
}

/**
 * Marks all notifications for the authenticated user as 'read'.
 * 
 * @param _userId - User ID parameter (currently unused as the API identifies the user via session).
 * @returns A promise resolving to true if the operation succeeded, false otherwise.
 */
export async function setAllRead(_userId: string): Promise<boolean> {
  const response = await fetch("/api/notifications", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ action: "markAllAsRead" }),
  });
  
  if (!response.ok) {
    return false;
  }

  const data = await response.json();
  return data.success === true;
}
