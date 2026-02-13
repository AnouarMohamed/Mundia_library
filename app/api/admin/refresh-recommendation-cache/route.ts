import { NextRequest, NextResponse } from "next/server";
import { refreshRecommendationCache } from "@/lib/admin/actions/recommendations";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const result = await refreshRecommendationCache();

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
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
