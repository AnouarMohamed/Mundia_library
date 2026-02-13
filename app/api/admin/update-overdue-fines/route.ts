import { NextRequest, NextResponse } from "next/server";
import { updateOverdueFines } from "@/lib/admin/actions/borrow";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function POST(request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const body = await request
      .json()
      .catch(() => ({ fineAmount: undefined as unknown }));

    let customFineAmount: number | undefined;
    if (body.fineAmount !== undefined) {
      const parsed = Number(body.fineAmount);
      if (!Number.isFinite(parsed) || parsed < 0) {
        return NextResponse.json(
          {
            success: false,
            error: "Invalid fine amount",
            message: "fineAmount must be a number greater than or equal to 0.",
          },
          { status: 400 }
        );
      }
      customFineAmount = parsed;
    }

    const results = await updateOverdueFines(customFineAmount);

    return NextResponse.json({
      success: true,
      message: `Updated fines for ${results.length} overdue record(s).`,
      results,
    });
  } catch (error) {
    console.error("Error updating overdue fines:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to update overdue fines",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
