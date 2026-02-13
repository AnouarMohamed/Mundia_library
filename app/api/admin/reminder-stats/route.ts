import { NextRequest, NextResponse } from "next/server";
import { getReminderStats } from "@/lib/admin/actions/reminders";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function GET(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const stats = await getReminderStats();

    return NextResponse.json({
      success: true,
      stats,
    });
  } catch (error) {
    console.error("Error fetching reminder stats:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch reminder stats",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
