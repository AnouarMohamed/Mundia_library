import { NextRequest, NextResponse } from "next/server";
import { getUserNotifications, markAllAsRead } from "@/lib/services/notification-service";
import {
  guardToResponse,
  requireApprovedUser,
} from "@/lib/security/auth-guards";
import {
  badRequestResponse,
  internalServerErrorResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for auth/session access.
 */
export const runtime = "nodejs";

/**
 * GET /api/notifications
 * Fetch notifications for the current authenticated user.
 */
export async function GET() {
  try {
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const notifications = await getUserNotifications(guard.user.id);
    return NextResponse.json({ success: true, notifications });
  } catch (error) {
    logError("notifications.fetch_failed", error);
    return internalServerErrorResponse();
  }
}

/**
 * POST /api/notifications
 * Supports global actions such as marking all notifications as read.
 */
export async function POST(request: NextRequest) {
  try {
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const { action } = await request.json();

    if (action === "markAllAsRead") {
      const success = await markAllAsRead(guard.user.id);
      return NextResponse.json({ success });
    }

    return badRequestResponse("Invalid action");
  } catch (error) {
    logError("notifications.update_all_failed", error);
    return internalServerErrorResponse();
  }
}
