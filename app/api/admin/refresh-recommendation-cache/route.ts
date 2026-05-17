import { NextRequest, NextResponse } from "next/server";
import { refreshRecommendationCache } from "@/lib/admin/actions/recommendations";
import { revalidateRecommendationsTag } from "@/lib/cache/revalidate";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * POST /api/admin/refresh-recommendation-cache
 * Refresh cached recommendation data.
 */
export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const result = await refreshRecommendationCache();
    revalidateRecommendationsTag();

    return NextResponse.json({
      success: true,
      message: result.message,
      cacheCleared: result.cacheCleared,
    });
  } catch (error) {
    console.error("Error refreshing recommendation cache:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to refresh recommendation cache",
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
