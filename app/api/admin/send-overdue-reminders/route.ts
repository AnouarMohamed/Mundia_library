import { NextRequest, NextResponse } from "next/server";
import { sendOverdueReminders } from "@/lib/admin/actions/reminders";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";
import { logAdminAction } from "@/lib/admin/audit";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * POST /api/admin/send-overdue-reminders
 * Send reminders for overdue borrows.
 */
export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const rawResults = await sendOverdueReminders();
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
      "SEND_OVERDUE_REMINDERS",
      undefined,
      "AUTOMATION",
      { processedCount: results.length, sentCount },
    );

    return NextResponse.json({
      success: true,
      message: `Processed ${results.length} overdue reminder(s). Sent ${sentCount}.`,
      results,
    });
  } catch (error) {
    console.error("Error sending overdue reminders:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to send overdue reminders",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
