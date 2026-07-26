import { NextRequest, NextResponse } from "next/server";
import { generateAllUserRecommendations } from "@/lib/admin/actions/recommendations";
import { requireAdminCapabilityRouteAccess } from "@/lib/admin/route-guard";
import { enforceSameOriginRequest } from "@/lib/security/same-origin";

/**
 * Use Node.js runtime for admin actions.
 */
export const runtime = "nodejs";

/**
 * POST /api/admin/generate-recommendations
 * Generate recommendations for all users.
 */
export async function POST(request: NextRequest) {
  try {
    const sameOriginResponse = enforceSameOriginRequest(request, {
      requireJson: true,
    });
    if (sameOriginResponse) return sameOriginResponse;

    const guard = await requireAdminCapabilityRouteAccess(
      "automation.execute",
    );
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
        message: "Request could not be completed",
      },
      { status: 500 }
    );
  }
}
