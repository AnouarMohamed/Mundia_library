import { NextRequest, NextResponse } from "next/server";
import { db } from "@/database/drizzle";
import { bookReviews } from "@/database/schema";
import { eq, and } from "drizzle-orm";
import {
  guardToResponse,
  requireApprovedUser,
} from "@/lib/security/auth-guards";
import { enforceRateLimit, isUuid } from "@/lib/security/api-request";
import {
  badRequestResponse,
  internalServerErrorResponse,
  jsonError,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * DELETE /api/reviews/delete/[reviewId]
 * Delete a review.
 */
export async function DELETE(
  _request: NextRequest,
  { params }: { params: Promise<{ reviewId: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    // CRITICAL: Authentication required for deleting reviews
    // Reviews can only be deleted by authenticated users who own the review
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const { reviewId } = await params;

    if (!reviewId) {
      return badRequestResponse("Review ID is required");
    }

    if (!isUuid(reviewId)) {
      return badRequestResponse("Invalid review ID");
    }

    // CRITICAL: Authorization check - user must own the review to delete it
    // Check if review exists and belongs to the user
    const existingReview = await db
      .select()
      .from(bookReviews)
      .where(
        and(
          eq(bookReviews.id, reviewId),
          eq(bookReviews.userId, guard.user.id)
        )
      )
      .limit(1);

    if (existingReview.length === 0) {
      return jsonError("Not Found", "Review not found", 404);
    }

    // Delete the review
    await db
      .delete(bookReviews)
      .where(
        and(
          eq(bookReviews.id, reviewId),
          eq(bookReviews.userId, guard.user.id)
        )
      );

    return NextResponse.json({
      success: true,
      message: "Review deleted successfully",
    });
  } catch (error) {
    logError("reviews.delete_failed", error);
    return internalServerErrorResponse();
  }
}
