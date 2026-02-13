import { NextRequest, NextResponse } from "next/server";
import { generateAllUserRecommendations } from "@/lib/admin/actions/recommendations";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function POST(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const results = await generateAllUserRecommendations();
    const totalRecommendations = results.reduce(
      (total, item) => total + item.recommendations.length,
      0
    );

    return NextResponse.json({
      success: true,
      results,
      totalUsers: results.length,
      totalRecommendations,
      message: `Generated ${totalRecommendations} recommendations for ${results.length} users.`,
    });
  } catch (error) {
    console.error("Error generating recommendations:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to generate recommendations",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
