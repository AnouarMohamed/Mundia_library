import { redirect } from "next/navigation";

/**
 * Redirect legacy /books to /all-books.
 */
const BooksIndexPage = () => {
  redirect("/all-books");
};

export default BooksIndexPage;
