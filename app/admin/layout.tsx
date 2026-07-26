/**
 * Admin Dashboard Layout
 * 
 * This layout is the shell for all administrative pages (/admin/*).
 * It enforces strict admin-only access and provides the admin-specific sidebar and header.
 * 
 * @module app/admin/layout
 */

import React, { ReactNode } from "react";
import { redirect } from "next/navigation";

import "@/styles/admin.css";
import Sidebar from "@/components/admin/Sidebar";
import Header from "@/components/admin/Header";
import { requireAdmin } from "@/lib/security/auth-guards";

/**
 * Ensures that admin pages are always rendered dynamically to reflect real-time data
 * and avoid stale cache states for administrative actions.
 */
export const dynamic = "force-dynamic";

/**
 * Admin layout component.
 * 
 * Uses the `requireAdmin` guard to ensure the user is authenticated and has the 'ADMIN' role.
 * Redirects unauthorized users to sign-in (if unauthenticated) or the home page (if not an admin).
 * 
 * @param {Object} props - Component properties
 * @param {ReactNode} props.children - Admin sub-page content
 */
const Layout = async ({ children }: { children: ReactNode }) => {
  // Security Guard: Check if the user has administrative privileges.
  const guard = await requireAdmin();

  // If unauthenticated, redirect to sign-in.
  if (!guard.ok && guard.status === 401) {
    redirect("/sign-in");
  }

  // If authenticated but not an admin, redirect to the main application.
  if (!guard.ok) {
    redirect("/");
  }

  return (
    <main className="admin-theme flex min-h-screen w-full flex-row">
      {/* Admin-specific sidebar for navigation */}
      <Sidebar session={guard.session} />

      <div className="admin-container">
        {/* Admin-specific header with search and user profile */}
        <Header session={guard.session} />
        
        {/* Admin page content */}
        {children}
      </div>
    </main>
  );
};

export default Layout;
