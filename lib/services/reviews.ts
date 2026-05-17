/**
 * Reviews Service
 *
 * This module provides a clean API for managing book reviews.
 * It strictly adheres to the "Pure Service" pattern:
 * - Contains only stateless fetch calls to the application's review API routes.
 * - Does NOT include React hooks or React Query logic.
 * - Centralizes data transformation and error handling for all review-related operations.
 *
 * This service is designed to be consumed by:
 * 1. Custom React Query hooks (for client-side state management).
 * 2. Server Components (for initial data fetching).
 */

import { ApiError, getApiErrorMessage } from "./apiError";

/**
 * Represents a book review as returned by the API.
 */
export interface Review {
  /** Unique identifier for the review. */
  id: string;
  /** Numerical rating given by the user (1-5). */
  rating: number;
  /** The text content of the review. */
  comment: string;
  /** Timestamp of when the review was originally created. */
  createdAt: Date | null;
  /** Timestamp of the last update to the review. */
  updatedAt: Date | null;
  /** Full name of the user who wrote the review. */
  userFullName: string;
  /** Whether the authenticated user owns this review. */
  isOwner: boolean;
  /** Unique identifier of the book being reviewed (optional). */
  bookId?: string;
}

/**
 * Detailed eligibility status for a user attempting to review a book.
 */
export interface ReviewEligibility {
  /** Overall success of the eligibility check request. */
  success: boolean;
  /** Whether the user is allowed to submit a review. */
  canReview: boolean;
  /** True if the user has already reviewed this book. */
  hasExistingReview: boolean;
  /** True if the user currently has the book borrowed. */
  isCurrentlyBorrowed: boolean;
  /** Descriptive reason if the user is not eligible. */
  reason: string;
}

/**
 * Data payload for creating a new review.
 */
export interface CreateReviewInput {
  /** Numerical rating (1-5). */
  rating: number;
  /** Review text. */
  comment: string;
}

/**
 * Data payload for updating an existing review.
 */
export interface UpdateReviewInput {
  /** Updated numerical rating (1-5). */
  rating: number;
  /** Updated review text. */
  comment: string;
}

/**
 * Standard response format for list-based review queries.
 */
export interface ReviewsListResponse {
  /** Indicates if the request was successful. */
  success: boolean;
  /** Array of review objects. */
  reviews: Review[];
}

/**
 * Standard response format for single-review operations.
 */
export interface ReviewResponse {
  /** Indicates if the operation was successful. */
  success: boolean;
  /** The review object (if applicable). */
  review: Review;
  /** Optional success or error message. */
  message?: string;
}

/**
 * Fetches all reviews associated with a specific book.
 * 
 * @param bookId - Unique identifier of the book.
 * @returns A promise resolving to an array of reviews.
 * @throws {ApiError} If the API request fails or returns an invalid format.
 */
export async function getBookReviews(bookId: string): Promise<Review[]> {
  if (!bookId) {
    throw new ApiError("Book ID is required", 400);
  }

  const response = await fetch(`/api/reviews/${bookId}`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  // Support both wrapped and unwrapped response formats
  if (data.success && data.reviews && Array.isArray(data.reviews)) {
    return data.reviews;
  }

  if (Array.isArray(data)) {
    return data;
  }

  throw new ApiError("Invalid response format from reviews API", 500);
}

/**
 * Checks if the authenticated user is allowed to review a specific book.
 * 
 * Eligibility Criteria:
 * 1. User must have previously borrowed and returned the book.
 * 2. User must not have an existing review for this book.
 * 
 * @param bookId - Unique identifier of the book.
 * @returns A promise resolving to the user's eligibility status.
 */
export async function getReviewEligibility(
  bookId: string
): Promise<ReviewEligibility> {
  if (!bookId) {
    throw new ApiError("Book ID is required", 400);
  }

  const response = await fetch(`/api/reviews/eligibility/${bookId}`, {
    method: "GET",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.success !== undefined) {
    return {
      success: data.success,
      canReview: data.canReview || false,
      hasExistingReview: data.hasExistingReview || false,
      isCurrentlyBorrowed: data.isCurrentlyBorrowed || false,
      reason: data.reason || "Unknown reason",
    };
  }

  throw new ApiError(
    "Invalid response format from review eligibility API",
    500
  );
}

/**
 * Submits a new review for a book.
 * 
 * @param bookId - Unique identifier of the book.
 * @param reviewData - The rating and comment for the new review.
 * @returns A promise resolving to the newly created review object.
 * @throws {ApiError} If validation fails or the user is ineligible.
 */
export async function createReview(
  bookId: string,
  reviewData: CreateReviewInput
): Promise<Review> {
  if (!bookId) {
    throw new ApiError("Book ID is required", 400);
  }

  // Client-side validation before sending the request
  if (!reviewData.rating || reviewData.rating < 1 || reviewData.rating > 5) {
    throw new ApiError("Rating must be between 1 and 5", 400);
  }

  if (!reviewData.comment || reviewData.comment.trim().length === 0) {
    throw new ApiError("Comment is required", 400);
  }

  const response = await fetch(`/api/reviews/${bookId}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      rating: reviewData.rating,
      comment: reviewData.comment.trim(),
    }),
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.success && data.review) {
    return data.review;
  }

  throw new ApiError("Invalid response format from create review API", 500);
}

/**
 * Updates an existing review written by the authenticated user.
 * 
 * @param reviewId - Unique identifier of the review to update.
 * @param reviewData - The new rating and comment.
 * @returns A promise resolving to the updated review object.
 */
export async function updateReview(
  reviewId: string,
  reviewData: UpdateReviewInput
): Promise<Review> {
  if (!reviewId) {
    throw new ApiError("Review ID is required", 400);
  }

  if (!reviewData.rating || reviewData.rating < 1 || reviewData.rating > 5) {
    throw new ApiError("Rating must be between 1 and 5", 400);
  }

  if (!reviewData.comment || reviewData.comment.trim().length === 0) {
    throw new ApiError("Comment is required", 400);
  }

  const response = await fetch(`/api/reviews/edit/${reviewId}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      rating: reviewData.rating,
      comment: reviewData.comment.trim(),
    }),
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (data.success && data.review) {
    return data.review;
  }

  throw new ApiError("Invalid response format from update review API", 500);
}

/**
 * Deletes a specific review.
 * 
 * @param reviewId - Unique identifier of the review to delete.
 * @returns A promise that resolves when the deletion is confirmed.
 */
export async function deleteReview(reviewId: string): Promise<void> {
  if (!reviewId) {
    throw new ApiError("Review ID is required", 400);
  }

  const response = await fetch(`/api/reviews/delete/${reviewId}`, {
    method: "DELETE",
    headers: {
      "Content-Type": "application/json",
    },
  });

  if (!response.ok) {
    throw new ApiError(await getApiErrorMessage(response), response.status);
  }

  const data = await response.json();

  if (!data.success) {
    throw new ApiError(
      data.message || "Failed to delete review",
      response.status
    );
  }
}

/**
 * Calculates aggregated rating statistics for a book.
 * This is a client-side aggregation based on fetched reviews.
 * 
 * @param bookId - Unique identifier of the book.
 * @returns A promise resolving to the average rating and total review count.
 */
export async function getBookRatingStats(bookId: string): Promise<{
  average: number;
  count: number;
}> {
  const reviews = await getBookReviews(bookId);

  if (reviews.length === 0) {
    return { average: 0, count: 0 };
  }

  // Compute average rating rounded to 1 decimal place
  const totalRating = reviews.reduce((sum, review) => sum + review.rating, 0);
  const average = totalRating / reviews.length;

  return {
    average: Math.round(average * 10) / 10,
    count: reviews.length,
  };
}
