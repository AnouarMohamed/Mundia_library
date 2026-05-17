import { revalidateTag } from "next/cache";
import { invalidatePattern } from "@/lib/cache/redis-cache";

/**
 * Revalidate cached book data.
 */
export function revalidateBooksTag() {
  revalidateTag("books");
}

/**
 * Revalidate cached recommendations.
 */
export function revalidateRecommendationsTag() {
  revalidateTag("recommendations");
}

/**
 * Revalidate all catalog-related tags.
 */
export function revalidateCatalogTags() {
  revalidateBooksTag();
  revalidateRecommendationsTag();
  void invalidatePattern("books:*");
}
