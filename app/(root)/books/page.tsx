import { redirect } from "next/navigation";

const BooksIndexPage = () => {
  redirect("/all-books");
};

export default BooksIndexPage;
