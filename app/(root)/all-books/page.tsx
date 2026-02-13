import { redirect } from "next/navigation";
import { and, asc, desc, eq, ilike, or, sql } from "drizzle-orm";
import { unstable_cache } from "next/cache";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import BookCollection from "@/components/BookCollection";
import { getSession } from "@/lib/session";

export const runtime = "nodejs";

const PAGE_SIZE = 12;

const getCachedGenres = unstable_cache(
  async () => {
    return db
      .selectDistinct({ genre: books.genre })
      .from(books)
      .orderBy(asc(books.genre));
  },
  ["all-books-genres-v1"],
  {
    revalidate: 300,
    tags: ["books"],
  }
);

const Page = async ({
  searchParams,
}: {
  searchParams: Promise<{
    search?: string;
    genre?: string;
    availability?: string;
    rating?: string;
    sort?: string;
    page?: string;
  }>;
}) => {
  const session = await getSession();
  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  const params = await searchParams;

  const search = params.search || "";
  const genre = params.genre || "";
  const availability = params.availability || "";
  const rating = params.rating || "";
  const sort = params.sort || "title";
  const page = Math.max(1, Number(params.page) || 1);

  const whereConditions = [];

  if (search) {
    const searchPattern = `%${search}%`;
    whereConditions.push(
      or(ilike(books.title, searchPattern), ilike(books.author, searchPattern))
    );
  }

  if (genre) {
    whereConditions.push(eq(books.genre, genre));
  }

  if (availability === "available") {
    whereConditions.push(sql`${books.availableCopies} > 0`);
  } else if (availability === "unavailable") {
    whereConditions.push(sql`${books.availableCopies} = 0`);
  }

  if (rating) {
    const minRating = Number(rating);
    if (!Number.isNaN(minRating)) {
      whereConditions.push(sql`${books.rating} >= ${minRating}`);
    }
  }

  let orderBy;
  switch (sort) {
    case "author":
      orderBy = asc(books.author);
      break;
    case "rating":
      orderBy = desc(books.rating);
      break;
    case "date":
      orderBy = desc(books.createdAt);
      break;
    case "title":
    default:
      orderBy = asc(books.title);
      break;
  }

  const offset = (page - 1) * PAGE_SIZE;
  const whereClause =
    whereConditions.length > 0 ? and(...whereConditions) : undefined;

  const [initialBooks, totalBooksResult, genresResult] = await Promise.all([
    db
      .select()
      .from(books)
      .where(whereClause)
      .orderBy(orderBy)
      .limit(PAGE_SIZE)
      .offset(offset),
    db
      .select({ count: sql<number>`count(*)` })
      .from(books)
      .where(whereClause),
    getCachedGenres(),
  ]);

  const totalBooks = Number(totalBooksResult[0]?.count || 0);
  const totalPages = Math.max(1, Math.ceil(totalBooks / PAGE_SIZE));

  return (
    <BookCollection
      initialBooks={initialBooks as Book[]}
      initialGenres={genresResult.map((item) => item.genre).filter(Boolean)}
      initialPagination={{
        currentPage: page,
        totalPages,
        totalBooks,
        booksPerPage: PAGE_SIZE,
      }}
      initialSearchParams={{
        search,
        genre,
        availability,
        rating,
        sort,
        page,
      }}
    />
  );
};

export default Page;
