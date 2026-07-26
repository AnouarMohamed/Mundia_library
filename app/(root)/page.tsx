/**
 * Root Entry Point
 * 
 * This is the landing page of the application (/).
 * Its primary responsibility is to route authenticated users to their destination (the library).
 * 
 * @module app/(root)/page
 */

import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

/**
 * Home page component (Server Component).
 * 
 * Checks for an active session and redirects the user accordingly.
 * If no session is found, redirects to /sign-in.
 * If authenticated, redirects to /library (the main app dashboard).
 */
const Home = async () => {
  // Fetch the current user session server-side.
  const session = await getSession();

  // Redirect to sign-in if no valid session or user ID is found.
  if (!session?.user?.id) {
    redirect("/sign-in");
  }

  // Default landing page for all authenticated users.
  // In a more complex scenario, this could route based on roles or user preferences.
  redirect("/library");
}

export default Home;
