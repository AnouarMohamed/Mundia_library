/**
 * Authentication Logout Action
 * 
 * Provides a server action to securely terminate a user's session.
 * It leverages NextAuth's signOut mechanism and ensures the user is
 * redirected to the sign-in page after logout.
 */

"use server";

import { signOut } from "@/auth";
import { redirect } from "next/navigation";

/**
 * Signs the current user out of the application.
 * 
 * This action terminates the active session and redirects the user
 * to the authentication entry point (/sign-in).
 */
export async function logoutAction() {
  await signOut();
  redirect("/sign-in");
}
