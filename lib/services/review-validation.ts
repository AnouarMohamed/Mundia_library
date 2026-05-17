export const MAX_REVIEW_COMMENT_LENGTH = 500;

type ReviewPayload = {
  rating?: unknown;
  comment?: unknown;
};

type ReviewValidationResult =
  | { ok: true; rating: number; comment: string }
  | { ok: false; message: string };

/**
 * Normalize and validate review mutation payloads before database writes.
 */
export const validateReviewPayload = (
  payload: unknown
): ReviewValidationResult => {
  if (!payload || typeof payload !== "object") {
    return { ok: false, message: "Invalid review payload" };
  }

  const { rating: rawRating, comment: rawComment } = payload as ReviewPayload;
  const rating = Number(rawRating);

  if (!Number.isInteger(rating) || rating < 1 || rating > 5) {
    return { ok: false, message: "Rating must be an integer from 1 to 5" };
  }

  if (typeof rawComment !== "string") {
    return { ok: false, message: "Comment is required" };
  }

  const comment = rawComment.trim();

  if (comment.length === 0) {
    return { ok: false, message: "Comment is required" };
  }

  if (comment.length > MAX_REVIEW_COMMENT_LENGTH) {
    return {
      ok: false,
      message: `Comment must be ${MAX_REVIEW_COMMENT_LENGTH} characters or fewer`,
    };
  }

  return { ok: true, rating, comment };
};
