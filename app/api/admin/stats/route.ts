/**
 * Admin Stats API Route
 *
 * GET /api/admin/stats
 *
 * Purpose: Get admin dashboard statistics.
 */

import { NextRequest, NextResponse } from "next/server";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";
import { getAdminDashboardStats } from "@/lib/admin/actions/dashboard";

export const runtime = "nodejs";

export async function GET(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const statsResult = await getAdminDashboardStats();
    if (!statsResult.success) {
      return NextResponse.json(
        {
          success: false,
          error: statsResult.error || "Failed to fetch admin statistics",
        },
        { status: 500 }
      );
    }

    return NextResponse.json({
      success: true,
      stats: statsResult.data,
    });
  } catch (error) {
    console.error("Error fetching admin stats:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch admin statistics",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
