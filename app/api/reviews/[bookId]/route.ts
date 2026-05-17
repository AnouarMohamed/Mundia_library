import { NextRequest, NextResponse } from "next/server";
import { randomUUID } from "crypto";
import { auth } from "@/auth";
import { db } from "@/database/drizzle";
import { bookReviews, users, borrowRecords } from "@/database/schema";
import { eq, and, desc } from "drizzle-orm";
import {
  guardToResponse,
  requireApprovedUser,
} from "@/lib/security/auth-guards";
import { enforceRateLimit, isUuid } from "@/lib/security/api-request";
import {
  badRequestResponse,
  internalServerErrorResponse,
  tooManyRequestsResponse,
} from "@/lib/security/api-response";
import { logError } from "@/lib/security/logger";
import { validateReviewPayload } from "@/lib/services/review-validation";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * GET /api/reviews/[bookId]
 * Get all reviews for a book.
 */
export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ bookId: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    // This endpoint returns public book reviews (reviews are public data, not user-specific)
    // Rate limiting provides protection against abuse while keeping it accessible for public book pages
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    const { bookId } = await params;

    if (!bookId) {
      return badRequestResponse("Book ID is required");
    }

    if (!isUuid(bookId)) {
      return badRequestResponse("Invalid book ID");
    }

    const session = await auth();
    const currentUserId = session?.user?.id;

    const reviewsResult = await db
      .select({
        id: bookReviews.id,
        userId: bookReviews.userId,
        rating: bookReviews.rating,
        comment: bookReviews.comment,
        createdAt: bookReviews.createdAt,
        updatedAt: bookReviews.updatedAt,
        userFullName: users.fullName,
      })
      .from(bookReviews)
      .innerJoin(users, eq(bookReviews.userId, users.id))
      .where(eq(bookReviews.bookId, bookId))
      .orderBy(desc(bookReviews.createdAt));

    const reviews = reviewsResult.map(({ userId, ...review }) => ({
      ...review,
      isOwner: Boolean(currentUserId && userId === currentUserId),
    }));

    return NextResponse.json({
      success: true,
      reviews,
    });
  } catch (error) {
    logError("reviews.fetch_failed", error);
    return internalServerErrorResponse();
  }
}

/**
 * POST /api/reviews/[bookId]
 * Create a new review for a book.
 */
export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ bookId: string }> }
) {
  try {
    // Rate limiting to prevent abuse (applies to both authenticated and unauthenticated users)
    const success = await enforceRateLimit();

    if (!success) {
      return tooManyRequestsResponse();
    }

    // CRITICAL: Authentication required for creating reviews
    // Reviews can only be created by authenticated users who have borrowed and returned the book
    const guard = await requireApprovedUser();
    if (!guard.ok) return guardToResponse(guard);

    const { bookId } = await params;

    if (!bookId) {
      return badRequestResponse("Book ID is required");
    }

    if (!isUuid(bookId)) {
      return badRequestResponse("Invalid book ID");
    }

    const reviewPayload = validateReviewPayload(
      await request.json().catch(() => null)
    );

    if (!reviewPayload.ok) {
      return badRequestResponse(reviewPayload.message);
    }

    // Check if user has borrowed this book before (for eligibility)
    const userBorrows = await db
      .select()
      .from(borrowRecords)
      .where(
        and(
          eq(borrowRecords.userId, guard.user.id),
          eq(borrowRecords.bookId, bookId),
          eq(borrowRecords.status, "RETURNED")
        )
      )
      .limit(1);

    if (userBorrows.length === 0) {
      return NextResponse.json(
        {
          success: false,
          error: "You must have borrowed this book to review it",
        },
        { status: 400 }
      );
    }

    // Check if user already has a review for this book
    const existingReview = await db
      .select()
      .from(bookReviews)
      .where(
        and(
          eq(bookReviews.userId, guard.user.id),
          eq(bookReviews.bookId, bookId)
        )
      )
      .limit(1);

    if (existingReview.length > 0) {
      return NextResponse.json(
        { success: false, error: "You have already reviewed this book" },
        { status: 400 }
      );
    }

    const reviewId = randomUUID();

    await db
      .insert(bookReviews)
      .values({
        id: reviewId,
        bookId,
        userId: guard.user.id,
        rating: reviewPayload.rating,
        comment: reviewPayload.comment,
      });

    const [newReview] = await db
      .select({
        id: bookReviews.id,
        rating: bookReviews.rating,
        comment: bookReviews.comment,
        createdAt: bookReviews.createdAt,
        updatedAt: bookReviews.updatedAt,
      })
      .from(bookReviews)
      .where(eq(bookReviews.id, reviewId))
      .limit(1);

    return NextResponse.json({
      success: true,
      review: newReview
        ? {
            ...newReview,
            userFullName: guard.user.name || "You",
            isOwner: true,
          }
        : newReview,
      message: "Review submitted successfully",
    });
  } catch (error) {
    logError("reviews.create_failed", error);
    return internalServerErrorResponse();
  }
}
