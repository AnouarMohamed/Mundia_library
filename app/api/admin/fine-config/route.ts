import { NextRequest, NextResponse } from "next/server";
import {
  getDailyFineAmount,
  setDailyFineAmount,
} from "@/lib/admin/actions/config";
import { requireAdminRouteAccess } from "@/lib/admin/route-guard";

export const runtime = "nodejs";

export async function GET(_request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const fineAmount = await getDailyFineAmount();

    return NextResponse.json({
      success: true,
      fineAmount,
      message: "Fine configuration loaded.",
    });
  } catch (error) {
    console.error("Error fetching fine configuration:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to fetch fine configuration",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const guard = await requireAdminRouteAccess();
    if (!guard.ok) {
      return guard.response;
    }

    const body = await request
      .json()
      .catch(() => ({ fineAmount: undefined as unknown }));
    const fineAmount = Number(body.fineAmount);

    if (!Number.isFinite(fineAmount) || fineAmount < 0) {
      return NextResponse.json(
        {
          success: false,
          error: "Invalid fine amount",
          message: "fineAmount must be a number greater than or equal to 0.",
        },
        { status: 400 }
      );
    }

    const updatedBy =
      typeof body.updatedBy === "string" && body.updatedBy.trim().length > 0
        ? body.updatedBy.trim()
        : guard.session.user?.email || "admin";

    const result = await setDailyFineAmount(fineAmount, updatedBy);

    if (!result.success) {
      return NextResponse.json(
        {
          success: false,
          error: "Failed to update fine configuration",
          message: result.error || "Unable to persist new fine amount.",
        },
        { status: 500 }
      );
    }

    return NextResponse.json({
      success: true,
      fineAmount,
      message: "Fine configuration updated successfully.",
    });
  } catch (error) {
    console.error("Error updating fine configuration:", error);
    return NextResponse.json(
      {
        success: false,
        error: "Failed to update fine configuration",
        message:
          error instanceof Error ? error.message : "Unknown error occurred",
      },
      { status: 500 }
    );
  }
}
