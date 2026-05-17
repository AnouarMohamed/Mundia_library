import { describe, expect, it } from "vitest";

import { validateReviewPayload } from "./review-validation";

describe("review validation", () => {
  it("normalizes valid review payloads", () => {
    expect(
      validateReviewPayload({ rating: "5", comment: "  useful review  " })
    ).toEqual({
      ok: true,
      rating: 5,
      comment: "useful review",
    });
  });

  it("rejects invalid ratings", () => {
    expect(validateReviewPayload({ rating: 6, comment: "good" })).toEqual({
      ok: false,
      message: "Rating must be an integer from 1 to 5",
    });
  });

  it("rejects empty or oversized comments", () => {
    expect(validateReviewPayload({ rating: 4, comment: "   " })).toEqual({
      ok: false,
      message: "Comment is required",
    });

    expect(
      validateReviewPayload({ rating: 4, comment: "a".repeat(501) })
    ).toEqual({
      ok: false,
      message: "Comment must be 500 characters or fewer",
    });
  });
});
