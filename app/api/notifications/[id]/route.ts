import { NextRequest, NextResponse } from "next/server";
import { markAsRead } from "@/lib/services/notification-service";
import {
  guardToResponse,
  requireApprovedUser,
} from "@/lib/security/auth-guards";
import {
  badRequestResponse,
  internalServerErrorResponse,
} from "@/lib/security/api-response";
import { isUuid } from "@/lib/security/api-request";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for auth/session access.
 */
export const runtime = "nodejs";

/**
 * PATCH /api/notifications/[id]
 * Mark a specific notification as read.
 */
export async function PATCH(
  _request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const { id } = await params;
    if (!isUuid(id)) {
      return badRequestResponse("Invalid notification ID");
    }

    // markAsRead enforces ownership for the current user.
    const success = await markAsRead(id, guard.user.id);

    return NextResponse.json({ success });
  } catch (error) {
    logError("notifications.mark_read_failed", error);
    return internalServerErrorResponse();
  }
}
