import { NextRequest, NextResponse } from "next/server";
import { sendDueSoonReminders } from "@/lib/admin/actions/reminders";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";
import { logAdminAction } from "@/lib/admin/audit";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * POST /api/admin/send-due-reminders
 * Send reminders for upcoming due dates.
 */
export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const rawResults = await sendDueSoonReminders();
    // Normalize delivery results for the admin UI.
    const results = rawResults.map((item) => ({
      userId: item.recordId,
      userEmail: item.userEmail,
      bookTitle: item.bookTitle,
      message: item.status === "sent" ? "Reminder sent" : "Reminder failed",
      sent: item.status === "sent",
      error: item.status === "failed" ? "Delivery failed" : undefined,
    }));

    const sentCount = results.filter((result) => result.sent).length;
    await logAdminAction(
      guard.user.id,
      "SEND_DUE_REMINDERS",
      undefined,
      "AUTOMATION",
      { processedCount: results.length, sentCount },
    );

    return NextResponse.json({
      success: true,
      message: `Processed ${results.length} due reminder(s). Sent ${sentCount}.`,
      results,
    });
  } catch (error) {
    console.error("Error sending due reminders:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to send due reminders",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
