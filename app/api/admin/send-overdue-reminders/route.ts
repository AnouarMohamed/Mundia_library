import { NextRequest, NextResponse } from "next/server";
import { sendOverdueReminders } from "@/lib/admin/actions/reminders";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const rawResults = await sendOverdueReminders();
    const results = rawResults.map((item) => ({
      userId: item.recordId,
      userEmail: item.userEmail,
      bookTitle: item.bookTitle,
      message: item.status === "sent" ? "Reminder sent" : "Reminder failed",
      sent: item.status === "sent",
      error: item.status === "failed" ? item.error : undefined,
    }));

    const sentCount = results.filter((result) => result.sent).length;

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
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
