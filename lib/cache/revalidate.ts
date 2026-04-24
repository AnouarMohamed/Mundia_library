import { revalidateTag } from "next/cache";

export function revalidateBooksTag() {
  revalidateTag("books");
}

export function revalidateRecommendationsTag() {
  revalidateTag("recommendations");
}

export function revalidateCatalogTags() {
  revalidateBooksTag();
  revalidateRecommendationsTag();
}
