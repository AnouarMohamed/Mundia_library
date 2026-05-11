import { NextRequest, NextResponse } from "next/server";
import { auth } from "@/auth";
import { getUserNotifications, markAllAsRead } from "@/lib/services/notification-service";

export const runtime = "nodejs";

/**
 * GET /api/notifications
 * Fetch notifications for the current authenticated user.
 */
export async function GET() {
  try {
    const session = await auth();
    if (!session?.user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const userId = session.user.id;
    if (!userId) {
      return NextResponse.json({ error: "User ID not found in session" }, { status: 400 });
    }

    const notifications = await getUserNotifications(userId);
    return NextResponse.json({ success: true, notifications });
  } catch (error) {
    console.error("Error in notifications API:", error);
    return NextResponse.json({ error: "Internal Server Error" }, { status: 500 });
  }
}

/**
 * POST /api/notifications/mark-all-read
 * Note: Since we want a single route file if possible, we can use query params or handle it in a sub-route.
 * For simplicity and idiomatic Next.js, let's use this route for global actions if needed, 
 * or create a separate one for specific ID actions.
 */
export async function POST(request: NextRequest) {
  try {
    const session = await auth();
    if (!session?.user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const userId = session.user.id;
    if (!userId) {
      return NextResponse.json({ error: "User ID not found in session" }, { status: 400 });
    }

    const { action } = await request.json();

    if (action === "markAllAsRead") {
      const success = await markAllAsRead(userId);
      return NextResponse.json({ success });
    }

    return NextResponse.json({ error: "Invalid action" }, { status: 400 });
  } catch (error) {
    console.error("Error in notifications POST API:", error);
    return NextResponse.json({ error: "Internal Server Error" }, { status: 500 });
  }
}
