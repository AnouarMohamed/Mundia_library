import { and, asc, count, eq, sql } from "drizzle-orm";
import { db } from "@/database/drizzle";
import { learningResources } from "@/database/schema";

export const LEARNING_RESOURCE_PAGE_SIZE = 24;

export async function listVerifiedLearningResources(input: {
  search: string;
  category: string;
  page: number;
}) {
  const conditions = [
    eq(learningResources.verificationStatus, "VERIFIED"),
  ];
  if (input.category) {
    conditions.push(eq(learningResources.category, input.category));
  }
  if (input.search) {
    conditions.push(
      sql<boolean>`to_tsvector('simple', coalesce(${learningResources.title}, '') || ' ' || coalesce(${learningResources.author}, '') || ' ' || coalesce(${learningResources.category}, '')) @@ websearch_to_tsquery('simple', ${input.search})`,
    );
  }
  const where = and(...conditions);
  const offset = (input.page - 1) * LEARNING_RESOURCE_PAGE_SIZE;
  const [items, totals, categories] = await Promise.all([
    db
      .select({
        id: learningResources.id,
        title: learningResources.title,
        author: learningResources.author,
        category: learningResources.category,
        language: learningResources.language,
        licenseExpression: learningResources.licenseExpression,
        sourceName: learningResources.sourceName,
        downloadUrl: learningResources.downloadUrl,
        readUrl: learningResources.readUrl,
      })
      .from(learningResources)
      .where(where)
      .orderBy(asc(learningResources.title), asc(learningResources.id))
      .limit(LEARNING_RESOURCE_PAGE_SIZE)
      .offset(offset),
    db.select({ count: count() }).from(learningResources).where(where),
    db
      .selectDistinct({ category: learningResources.category })
      .from(learningResources)
      .where(eq(learningResources.verificationStatus, "VERIFIED"))
      .orderBy(asc(learningResources.category)),
  ]);
  return {
    items,
    total: Number(totals[0]?.count ?? 0),
    categories: categories.map(({ category }) => category),
  };
}

export async function getVerifiedLearningResource(id: string) {
  const [resource] = await db
    .select()
    .from(learningResources)
    .where(
      and(
        eq(learningResources.id, id),
        eq(learningResources.verificationStatus, "VERIFIED"),
      ),
    )
    .limit(1);
  return resource ?? null;
}
