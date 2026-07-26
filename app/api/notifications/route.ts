/**
 * User Notifications API Endpoint
 *
 * Provides management of user-specific notifications.
 * Supports retrieving a list of notifications and performing bulk actions like marking all as read.
 *
 * @module app/api/notifications/route
 */

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
import { enforceSameOriginRequest } from "@/lib/security/same-origin";

/**
 * Force Node.js runtime for session management and database services.
 */
export const runtime = "nodejs";

/**
 * GET Handler for /api/notifications
 *
 * Retrieves all notifications for the currently authenticated user.
 *
 * @returns {NextResponse} JSON response containing the notifications array
 */
export async function GET() {
  try {
    // 1. Security Guard: Ensure the user is authenticated and approved.
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    // 2. Fetch notifications from the service layer.
    const notifications = await getUserNotifications(guard.user.id);

    return NextResponse.json({ success: true, notifications });
  } catch (error) {
    logError("notifications.fetch_failed", error);
    return internalServerErrorResponse();
  }
}

/**
 * POST Handler for /api/notifications
 *
 * Handles bulk actions for the user's notification list.
 * Supported Actions:
 * - "markAllAsRead": Marks every notification for the current user as read.
 *
 * @param {NextRequest} request - Next.js Request object containing the action payload
 * @returns {NextResponse} JSON response indicating success status
 */
export async function POST(request: NextRequest) {
  try {
    const sameOriginResponse = enforceSameOriginRequest(request, {
      requireJson: true,
    });
    if (sameOriginResponse) return sameOriginResponse;

    // 1. Security Guard: Ensure the user is authenticated and approved.
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    // 2. Parse and validate the action payload.
    const body = await request.json().catch(() => null);

    if (!body || typeof body.action !== "string") {
      return badRequestResponse("Invalid action");
    }

    const { action } = body;

    // 3. Execute the requested action.
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
