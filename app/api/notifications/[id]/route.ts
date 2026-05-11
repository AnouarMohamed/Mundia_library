import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/auth";
import { markAsRead } from "@/lib/services/notification-service";

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
    const session = await auth();
    if (!session?.user?.id) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const { id } = await params;
    const success = await markAsRead(id, session.user.id);

    return NextResponse.json({ success });
  } catch (error) {
    console.error("Error in notification update API:", error);
    return NextResponse.json({ error: "Internal Server Error" }, { status: 500 });
  }
}
