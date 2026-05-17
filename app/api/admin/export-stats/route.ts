import { NextRequest, NextResponse } from "next/server";
import { getExportStats } from "@/lib/admin/actions/data-export";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * GET /api/admin/export-stats
 * Fetch export metrics for the admin dashboard.
 */
export async function GET(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const stats = await getExportStats();

    return NextResponse.json({
      success: true,
      stats,
    });
  } catch (error) {
    console.error("Error fetching export stats:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch export stats",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
