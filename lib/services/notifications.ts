/**
 * Notification Service (Client-side)
 * 
 * This file contains client-side service functions for managing user notifications.
 * It uses fetch() to call the notifications API routes.
 */

/**
 * Interface for a notification
 */
export interface Notification {
  id: string;
  userId: string;
  title: string;
  message: string;
  type: "INFO" | "SUCCESS" | "WARNING" | "ERROR";
  isRead: boolean;
  createdAt: Date | null;
}

/**
 * Fetch notifications for the current user
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
 * Mark a notification as read
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
 * Mark all notifications as read for a user
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
