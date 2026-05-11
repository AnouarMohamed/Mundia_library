/**
 * Admin Books List Page
 *
 * Server Component that fetches all books server-side for SSR.
 * Passes initial data to Client Component for React Query integration.
 */

import React from "react";
import { db } from "@/database/drizzle";
import { books } from "@/database/schema";
import AdminBooksList from "@/components/AdminBooksList";

/**
 * Use Node.js runtime for DB access.
 */
export const runtime = "nodejs";

/**
 * Admin list view for managing books.
 */
const Page = async () => {
  // Fetch all books server-side for SSR
  const allBooks = await db.select().from(books);

  return <AdminBooksList initialBooks={allBooks as Book[]} />;
};

export default Page;
