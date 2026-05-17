import { NextRequest, NextResponse } from "next/server";
import { updateTrendingBooks } from "@/lib/admin/actions/recommendations";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * POST /api/admin/update-trending-books
 * Recompute trending books for the catalog.
 */
export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const result = await updateTrendingBooks();

    return NextResponse.json({
      success: true,
      message: result.message,
      trendingCount: result.trendingCount,
    });
  } catch (error) {
    console.error("Error updating trending books:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to update trending books",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
