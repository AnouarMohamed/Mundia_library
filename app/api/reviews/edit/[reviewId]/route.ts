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
import { validateReviewPayload } from "@/lib/services/review-validation";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * PUT /api/reviews/edit/[reviewId]
 * Update a review.
 */
export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ reviewId: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    // CRITICAL: Authentication required for updating reviews
    // Reviews can only be updated by authenticated users who own the review
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const { reviewId } = await params;

    if (!reviewId) {
      return badRequestResponse("Review ID is required");
    }

    if (!isUuid(reviewId)) {
      return badRequestResponse("Invalid review ID");
    }

    const reviewPayload = validateReviewPayload(
      await request.json().catch(() => null)
    );

    if (!reviewPayload.ok) {
      return badRequestResponse(reviewPayload.message);
    }

    // CRITICAL: Authorization check - user must own the review to edit it
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

    await db
      .update(bookReviews)
      .set({
        rating: reviewPayload.rating,
        comment: reviewPayload.comment,
        updatedAt: new Date(),
      })
      .where(
        and(
          eq(bookReviews.id, reviewId),
          eq(bookReviews.userId, guard.user.id)
        )
      );

    const [updatedReview] = await db
      .select({
        id: bookReviews.id,
        rating: bookReviews.rating,
        comment: bookReviews.comment,
        updatedAt: bookReviews.updatedAt,
      })
      .from(bookReviews)
      .where(eq(bookReviews.id, reviewId))
      .limit(1);

    return NextResponse.json({
      success: true,
      review: updatedReview
        ? {
            ...updatedReview,
            isOwner: true,
          }
        : updatedReview,
      message: "Review updated successfully",
    });
  } catch (error) {
    logError("reviews.update_failed", error);
    return internalServerErrorResponse();
  }
}

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
