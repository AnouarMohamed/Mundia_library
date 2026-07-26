/**
 * Admin Statistics API Endpoint
 * 
 * Provides high-level dashboard metrics for administrators.
 * This includes counts for users, books, borrow records, and fine summaries.
 * 
 * @module app/api/admin/stats/route
 */

import { NextRequest, NextResponse } from "next/server";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";
import { getAdminDashboardStats } from "@/lib/admin/actions/dashboard";

/**
 * Force Node.js runtime for complex aggregation queries.
 */
export const runtime = "nodejs";

/**
 * GET Handler for /api/admin/stats
 * 
 * Fetches aggregated statistics for the admin dashboard.
 * Requires administrative privileges.
 * 
 * @param {NextRequest} _request - Next.js Request object (unused)
 * @returns {NextResponse} JSON response containing dashboard statistics
 */
export async function GET(_request: NextRequest) {
  try {
    // 1. Administrative Security Guard: Ensure only authorized admins can access stats.
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    // 2. Fetch statistics via the dashboard service action.
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

    // 3. Return the aggregated data.
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
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
