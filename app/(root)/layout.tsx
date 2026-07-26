/**
 * Root Application Layout
 * 
 * This layout is used for the main application pages (library, profile, etc.).
 * It enforces that only authenticated and approved users can access these routes.
 * It also provides the global header and common container styling.
 * 
 * @module app/(root)/layout
 */

import { ReactNode } from "react";
import { redirect } from "next/navigation";
import Header from "@/components/Header";
import { requireApprovedUser } from "@/lib/security/auth-guards";

/**
 * Root application layout component.
 * 
 * Uses the `requireApprovedUser` guard to ensure the user is authenticated and has an 'APPROVED' status.
 * If the guard fails, it redirects to the sign-in page.
 * 
 * @param {Object} props - Component properties
 * @param {ReactNode} props.children - Main page content
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  // Enforce security: only approved users can access the root application routes.
  const guard = await requireApprovedUser();

  // If not authenticated or not approved, redirect to sign-in.
  if (!guard.ok) {
    redirect("/sign-in");
  }

  return (
    <main className="root-container">
      {/* Global Application Header with user session */}
      <Header session={guard.session} />
      
      {/* Page Content */}
      {children}
    </main>
  );
};

export default Layout;
