"use server";

import { signOut } from "@/auth";
import { redirect } from "next/navigation";

/**
 * Sign the user out and redirect to sign-in.
 */
export async function logoutAction() {
  await signOut();
  redirect("/sign-in");
}
