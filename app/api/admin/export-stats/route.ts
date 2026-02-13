import { NextRequest, NextResponse } from "next/server";
import { getExportStats } from "@/lib/admin/actions/data-export";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

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
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
